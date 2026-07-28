# vEnderchest public API (for vAntiDupe and similar monitors)

Read-only, additive, observation-only surface for external plugins. **vEnderchest's duplication
protection (session serialization + revision compare-and-swap) works identically whether or not
anything consumes this API.** Nothing here can cancel a transaction, veto an open, or mutate a
vault — it exists purely so a passive monitor like vAntiDupe can alert on what already happened.

## Package layout

```
com.valerin.venderchest.api          - public interfaces, DTOs, enums (stable contract)
com.valerin.venderchest.api.event    - public Bukkit events
```

Everything else in the plugin (`com.valerin.venderchest.gui`, `.session`, `.storage`, …) is
internal and may change at any time without notice — never depend on it directly.

## Depending on it as `compileOnly`

Building the plugin (`./gradlew.bat build`) also produces a lightweight, dependency-free jar
containing **only** the `com.valerin.venderchest.api` package — no internal classes, no shaded
HikariCP/JDBC drivers/H2:

```
build/libs/vEnderchest-<version>-api.jar
```

Pick whichever of these fits your build:

**Option A — local file dependency (simplest, no publishing required):**

```kotlin
dependencies {
    compileOnly(files("../vEnderchest/build/libs/vEnderchest-1.1.0-api.jar"))
}
```

**Option B — publish to your local Maven repo once, then depend on coordinates:**

```kotlin
// in vEnderchest's build.gradle.kts, add the maven-publish plugin and:
publishing {
    publications {
        create<MavenPublication>("api") {
            artifact(tasks.named("apiJar"))
            artifactId = "vEnderchest-api"
        }
    }
}
// then: ./gradlew.bat publishToMavenLocal
```

```kotlin
// in vAntiDupe's build.gradle.kts:
repositories { mavenLocal() }
dependencies {
compileOnly("com.valerin:vEnderchest-api:1.1.0")
}
```

**Option C — depend on the full plugin jar** (works, but pulls in the full shaded jar as a compile
dependency; prefer the `-api` jar above):

```kotlin
compileOnly(files("../vEnderchest/build/libs/vEnderchest-1.1.0.jar"))
```

In every case, mark vEnderchest as `softdepend` (not `depend`) in vAntiDupe's `plugin.yml`, and null-
check `Bukkit.getServicesManager().getRegistration(VEnderChestApi.class)` before use — vEnderchest
may not be installed, and the vault protection does not require it to be.

## Obtaining the service

```java
RegisteredServiceProvider<VEnderChestApi> registration =
        Bukkit.getServicesManager().getRegistration(VEnderChestApi.class);
if (registration == null) return; // vEnderchest not installed, or not yet enabled
VEnderChestApi api = registration.getProvider();
```

Or, if you only need a one-off lookup: `Bukkit.getServicesManager().load(VEnderChestApi.class)`.

## `VEnderChestApi`

```java
public interface VEnderChestApi {
    Optional<VaultSessionView> activeSession(UUID actorUuid);
    Collection<VaultSessionView> activeSessions();
    Optional<VaultSnapshot> snapshot(UUID ownerUuid, String vaultId);
}
```

- `activeSession` / `activeSessions()` — read the in-memory session registry; cheap, safe to call
  from the main thread.
- `snapshot(ownerUuid, vaultId)` — **performs synchronous database I/O.** Call it from an async task,
  never from the main thread. `vaultId` is the page number as a string (e.g. `"1"`), matching the
  location-key format below.

Every returned object is immutable:

- `VaultSessionView(sessionId, ownerUuid, actorUuid, vaultId, revision, openedAt, state)` — plain
  record of immutable types.
- `VaultSnapshot(ownerUuid, vaultId, revision, List<VaultSlot> slots)` — `slots` is
  `List.copyOf(...)`, unmodifiable.
- `VaultSlot(slot, item, locationKey)` — `item` is defensively cloned both on construction and on
  every call to `item()`; mutating a returned `ItemStack` never affects plugin state, and mutating
  the plugin's internal item never affects a value you've already read.
- `PublicSessionState` — `OPENING | ACTIVE | COMMITTING | CLOSED`.

## Location keys

Every slot has a stable identifier:

```
venderchest:<ownerUuid>:vault:<vaultId>:slot:<slot>
```

Built by `LocationKeys.of(ownerUuid, vaultId, slot)` — use this instead of constructing the string
yourself if you're also depending on the api jar.

## Events (`com.valerin.venderchest.api.event`)

All four extend plain `org.bukkit.event.Event` — **none implement `Cancellable`**, by design: a
listener cannot veto anything through this API even by mistake. All four are fired **only on the
main server thread**, documented on each class's Javadoc.

### `VaultSessionOpenedEvent`
Fired once a session has been authoritatively registered and its content loaded.
`sessionId, ownerUuid, actorUuid, vaultId, revision, timestamp, serverId`.

### `VaultTransactionCommittedEvent`
Fired **only after** a transaction has been durably confirmed by the database (the revision
compare-and-swap succeeded) — never optimistically before that.
`transactionId, sessionId, ownerUuid, actorUuid, vaultId, baseRevision, committedRevision,
timestamp, serverId, List<SlotChange> slotChanges`.

Each `transactionId` is generated exactly once, inside the code path that can itself only run once
per session commit (guarded by an internal compare-and-swap) — this event cannot fire twice for the
same transaction.

`SlotChange(slot, action, before, after, amountBefore, amountAfter, locationKey)` — `action` is one
of `INSERT | REMOVE | REPLACE | MOVE`; `before`/`after` are defensively cloned `ItemStack`s (`null`
for an empty slot). `MOVE` is a best-effort forensic hint (an unambiguous single same-kind/same-
amount relocate); ambiguous multi-item shuffles are reported as separate `INSERT`/`REMOVE` entries
rather than guessed at.

### `VaultSessionClosedEvent`
Fired once a session has been closed and its key freed.
`sessionId, ownerUuid, actorUuid, vaultId, lastRevision, reason, timestamp, serverId`.

`reason` (`CloseReason`): `CLIENT_CLOSE | REOPEN | LOGOUT | KICK | CONFLICT | SHUTDOWN |
ADMIN_FORCE`.

### `VaultConflictEvent`
Fired whenever vEnderchest's own protection rejects, refreshes, or discards something because of a
conflicting concurrent operation. **By the time this fires, vEnderchest has already resolved the
conflict safely on its own** — this is purely a notification for alerting.
`sessionId, ownerUuid, actorUuid, vaultId, type, expectedRevision, actualRevision, timestamp,
serverId`.

`type` (`ConflictType`): `STALE_REVISION | CONCURRENT_SESSION | LOST_UPDATE |
EVENT_FROM_CLOSED_SESSION | SUSPICIOUS_REOPEN`. `expectedRevision`/`actualRevision` are `-1` when
not applicable to the conflict type (e.g. `CONCURRENT_SESSION`).

`getAttemptedChanges()` devuelve un diff inmutable cuando el conflicto ocurrió al intentar
confirmar cambios de una revisión obsoleta. Esto permite a vAntiDupe identificar el ítem afectado
sin acceder al inventario vivo. Para conflictos que ocurren antes de existir una transacción
(`CONCURRENT_SESSION`, `SUSPICIOUS_REOPEN`, etc.) la lista queda vacía.

## Threading summary

| Call | Thread |
|---|---|
| `activeSession` / `activeSessions()` | Any (reads an in-memory map) |
| `snapshot(...)` | **Caller must be off the main thread** — synchronous DB I/O |
| All four events | Always dispatched on the main server thread |

## What is never exposed

No SQL connections, no internal DAOs (`Storage`, `AbstractJdbcStorage`, …), no mutable `Inventory`
references, and no way to reach the live session registry or cancel/modify a transaction. Every
`ItemStack` that crosses this boundary is a clone; every collection is unmodifiable.
