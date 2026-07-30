# Vault duplication fix — root cause, design, and verification

This document covers the item-duplication vulnerability found in vEnderchest's vault GUI/session
handling, the fix, and how to verify it defensively. It intentionally does not describe any
exploitation technique beyond what was already reported and fixed.

## Root cause

Confirmed by reading the code (not guessed), all in the pre-fix version of these files:

1. **`GuiManager.openPageInternal`** (`src/main/java/com/valerin/venderchest/gui/GuiManager.java`,
   old lines 93–115) registered the *new* `OpenSession` via `sessions.put(uuid, session)` **before**
   the old inventory was actually closed. `Player#openInventory` closes whatever the player
   currently has open as a side effect, so `InventoryCloseEvent` for the *old* inventory only fired
   *after* the session map already pointed at the *new* session.
2. **`GuiManager.onClose`** (old lines ~145–153) looked up the session via `sessions.get(uuid)` and
   compared `session.getInventory()` against the closed inventory. Because the map had already been
   overwritten in step 1, this identity check failed for the old inventory and the pending save
   (`flushAsync`) was silently skipped — the old session's item removal was **never persisted**.
3. The new session's async load checked an in-memory `writeBuffer` for a pending write keyed by
   `uuid:page`; since the old session's flush never ran, nothing was buffered, so it fell through to
   `storage.loadPage(uuid, page)`, which returned the **last row actually committed to the
   database** — i.e. the state *before* the item was removed. That stale copy rendered in the new
   GUI while the player was also physically holding the copy already taken from the old session.
   Taking it again produced a duplicate.
4. **`AbstractJdbcStorage.savePage`/`upsertSql()`** was a blind last-write-wins upsert
   (`INSERT OR REPLACE` / `ON DUPLICATE KEY UPDATE`) with no revision or optimistic-concurrency
   check at all, so independent of the race above, two async saves completing out of order (or two
   servers sharing one MySQL schema) could silently overwrite newer state with older state.
5. Nothing treated "a session for this owner+vault is already open" as a reason to serialize a
   second open — a second `/ec 1` started a second, fully independent load unconditionally.
6. The old `openSeq` counter (`GuiManager.java`, an `AtomicInteger` per player) only protected a
   *third*, later, stale async callback from clobbering a session after a *second* open had already
   won the race. It did not stop exactly two overlapping opens (the reported exploit), and being an
   in-JVM counter it gave zero protection across two servers sharing one MySQL database.
7. **Secondary latent bug found during the audit**: `InterceptListener.onQuit` cleared session
   bookkeeping based on a comment assuming `InventoryCloseEvent` always fires before
   `PlayerQuitEvent`. That ordering is not guaranteed across Bukkit/Paper forks; if quit-cleanup ran
   first, the close-triggered save would silently no-op and a dirty page could be lost on logout.

None of this depended on the client sending (or not sending) a close packet — the race exists purely
in server-side bookkeeping, which is exactly why a client mod that skips the normal close flow can
trigger it.

## Fix: authoritative sessions + revision compare-and-swap

### Session registry

`com.valerin.venderchest.session.VaultSessionRegistry` (pure Java, no Bukkit dependency) is now the
single source of truth for "which session, if any, owns vault X of player Y right now". It keys
sessions by `VaultKey(ownerUuid, vaultId)` — `vaultId` is the existing page number stringified, the
same vault identity the plugin already used, not a new concept.

Each `VaultSession` carries: `sessionId` (UUID), `ownerUuid`, `actorUuid` (the player or admin who
opened it), `vaultId`, the revision it was loaded at, an opaque inventory-instance token, the open
timestamp, and a state — `OPENING → ACTIVE → COMMITTING → ACTIVE → … → CLOSED`. Every transition is
compare-and-swapped, so a given transition (e.g. "commit this session") can only ever be *performed*
once, no matter how many trigger paths race to attempt it.

`beginOpen(owner, actor, vaultId)`:
- No session exists for that key → **Created**, registers a new session in `OPENING`.
- A session exists, same actor → **Supersede** — nothing new is registered; the caller must fully
  commit and close the existing session first, then retry.
- A session exists, different actor → **Rejected** — the open attempt is refused and the actor is
  notified; there is no queue.

`GuiManager` always tries to commit-and-close whatever its own bookkeeping shows the actor
currently has open *before* asking the registry to open something new (`closeCurrentVaultThenRun`).
Because that happens purely from server-side state, it doesn't matter whether the client ever sent
a close packet. The registry's `Supersede`/`Rejected` handling (`GuiManager.resolveSupersede`) is
the authoritative fallback for the narrower timing case where two opens for the same actor race
inside the same tick, before the first one's asynchronous load has even completed — exercised
directly by `VaultSessionRegistryTest`.

The net effect for the reported case: `/ec 1`, take an item, `/ec 1` again without a clean close →
the server commits the first session (persisting the take) **before** it ever starts loading the
second one. The second session is only ever allowed to load *after* that commit is durably
confirmed, so it can never render a copy of an item that was already removed.

### Revision compare-and-swap

`ec_pages` gained a `revision BIGINT NOT NULL DEFAULT 0` column (additive migration, see
[`MIGRATION_REVISION.md`](MIGRATION_REVISION.md)). Saving now goes through:

```sql
UPDATE ec_pages SET data = ?, revision = revision + 1
WHERE uuid = ? AND page = ? AND revision = ?
```

checking that exactly one row was updated (`Storage.savePageIfRevision`, `AbstractJdbcStorage`). If
zero rows matched, the persisted revision has already moved past what the caller loaded — the write
is dropped entirely (never applied, never partially applied), the conflict is logged and reported
through `VaultConflictEvent`, and the session is discarded rather than retried blindly. This is the
hard safety net: even if the in-JVM session registry were somehow bypassed, or two servers sharing
one MySQL database both had a session open for the same vault, the loser's write can never silently
overwrite the winner's — see `RevisionCasTest` for the two-out-of-order-saves reproduction.

En una reapertura del mismo servidor, `GuiManager` congela la sesión en estado `COMMITTING` antes de
iniciar el guardado. `GuiListener` rechaza clicks y drags mientras la sesión no sea `ACTIVE`. Al
terminar el commit, el servidor invalida la sesión y cierra la GUI antigua antes de retirar su
bookkeeping y comenzar la carga nueva. Esto elimina tanto la ventana durante el I/O como la ventana
entre el commit y la siguiente apertura.

Importante: Bukkit permite que un objeto se mueva dentro de una GUI `ACTIVE` antes del guardado al
cerrar. El CAS evita sobrescribir una revisión nueva, pero por sí solo no puede retirar de forma
segura un objeto que ya alcanzó el inventario del actor en otro servidor. Por ello este parche
garantiza el caso reproducido dentro de un servidor, pero no debe presentarse como bloqueo
distribuido completo. Un `VaultConflictEvent` informa el diff intentado para que vAntiDupe alerte.

### Corrección para filas migradas con revisión 0

Las instalaciones anteriores reciben `revision = 0` al añadir la columna. Una implementación anterior
confundía esas filas existentes con páginas todavía inexistentes e intentaba únicamente un
`INSERT`, que fallaba por la clave primaria y producía `STALE_REVISION 0→0`. Como el objeto ya
había llegado al inventario del jugador, omitir ese guardado permitía que reapareciera al abrir
otra vez el vault.

En el baseline 1.0.0, un guardado basado en revisión 0 primero ejecuta un `UPDATE ... WHERE revision = 0`;
si no existe esa fila, intenta el `INSERT` inicial. Ambos caminos son atómicos: una sola escritura
puede avanzar a revisión 1. `MigratedRevisionZeroJdbcTest` reproduce una base pre-revisión real y
comprueba también que un segundo intento obsoleto es rechazado.

## What this protects against (and how)

| Scenario | Mechanism |
|---|---|
| `/ec 1` twice while the first is still open | `closeCurrentVaultThenRun` self-heals from server state before opening anything new |
| Two opens in the exact same tick | `VaultSessionRegistry`'s `Supersede` path, driven by session state, not client timing |
| GUI closed without a client packet | Every trigger (reopen, navigate, quit, kick, autosave, shutdown) commits independently; none of them depend on `InventoryCloseEvent` firing |
| Click/drag durante commit o desde sesión antigua | `GuiManager.validateSessionOrReject` exige sesión `ACTIVE`, token de inventario idéntico y propiedad actual del vault |
| Two async saves completing out of order | Revision CAS: only the save whose `expectedRevision` still matches can succeed |
| Dos servidores escribiendo el mismo vault MySQL | El CAS evita la pérdida silenciosa de datos y emite conflicto, pero no garantiza por sí solo recuperar un objeto ya retirado en la sesión perdedora |
| Logout / kick mid-session | `InterceptListener` explicitly commits-and-closes on both `PlayerQuitEvent` and `PlayerKickEvent`; idempotent with a racing `InventoryCloseEvent` because the commit CAS can only succeed once |
| Plugin reload / shutdown | `GuiManager.closeAll` — the one deliberate synchronous-DB-write exception, since Bukkit does not reliably finish scheduled async tasks during disable |

## Reproducing the defensive verification (no further exploitation detail)

This reproduces the *original report* against the fixed build to confirm exactly one copy of the
item exists afterward — it does not describe any technique beyond what was already reported.

1. Start the server with the built jar (`build/libs/vEnderchest-<version>.jar`).
2. As a test player with a known, easily countable item (e.g. a single unstackable tool) in vault
   page 1, run `/ec 1`.
3. Take the item out of the vault into your player inventory.
4. Without closing the GUI normally, issue `/ec 1` again (any means of re-invoking the command while
   the GUI is still open server-side reproduces the same server-side condition).
5. Confirm:
   - The vault page that opens does **not** show the item again.
   - The item still exists exactly once, in the player's inventory.
   - The server log contains a `reopen_detected` audit line for that owner/vault, followed by a
     `commit` line (not a `conflict` line) — i.e. the first session's take was persisted before the
     second session loaded.
6. Repeat step 4 a few times in quick succession; the item count in the player's inventory must stay
   at exactly one throughout, and `allActive()`-equivalent state (only one session per vault) never
   shows more than one live session for that vault/owner in the logs.

Automated coverage of this exact scenario, and of the async/ordering edge cases that can't be driven
from real client input, lives in `VaultSessionRegistryTest#reportedExploit_reopenBeforeCommit_...`
and `RevisionCasTest`.

## Límite distribuido pendiente

Hasta implementar un lease/bloqueo distribuido o commits por operación antes de entregar cada
objeto, un mismo vault no debe abrirse en modo escritura simultáneamente desde dos servidores que
compartan MySQL. El revision CAS permanece activo como protección de integridad y señal forense,
pero un conflicto CAS debe tratarse como posible duplicación y revisarse mediante vAntiDupe.

## Reversión de transferencias rechazadas

Un conflicto CAS conserva correctamente la página persistida, pero el cliente puede haber movido
ya un objeto entre el vault y su inventario. Antes de esta corrección, la navegación continuaba:
un retiro rechazado podía permanecer en la página original y luego depositarse en otra página.

Ahora el servidor calcula el balance neto por tipo exacto de objeto. Ante conflicto:

- retira del jugador cualquier cantidad cuyo retiro no se confirmó;
- devuelve al jugador cualquier depósito que no se confirmó;
- ignora movimientos internos entre slots, cuyo balance neto es cero;
- no abre la página siguiente si la reversión no pudo completarse.

También se bloquea soltar objetos desde el cursor o desde el vault mientras la sesión está abierta,
manteniendo todo lo necesario para una posible reversión dentro de inventarios controlables.
