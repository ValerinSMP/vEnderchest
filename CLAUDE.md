# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

vEnderchest is a Paper/Spigot Minecraft server plugin (Java 21, group `com.valerin`) that replaces the
vanilla ender chest with a multi-page, GUI-based, database-backed vault. It supports SQLite or MySQL
storage, per-permission page limits, purchasable extra pages, one-time migration from the vanilla
ender chest and from the AxVaults plugin, and an optional PlaceholderAPI hook.

## Build commands

Windows-only wrapper is checked in (`gradlew.bat`); there is no Unix `gradlew` script.

```bash
./gradlew.bat build
```

- `build` depends on `shadowJar` (see `build.gradle.kts`), so `build` always produces the relocated fat
  jar used for deployment — there's no separate "just compile" packaging step to reach for.
- Output jar: `build/libs/vEnderchest-<version>.jar`.
- No test suite/framework is configured in this project (no test source set, no test task usage).

## Architecture

### Plugin bootstrap

`VEnderchest.java` (`onEnable`) is the composition root: it builds `ConfigManager` → `Storage` →
`GuiManager` → `MigrationManager` in that order, then registers listeners/commands. Read this file
first when tracing how components are wired together. `reload()` (triggered by `/ecadmin reload`) calls
`guiManager.closeAll()` (flush + force-close open GUIs) before `configManager.reload()` re-reads every
YAML file — order matters, since a config reload while GUIs are open would desync in-memory sessions
from on-disk config (e.g. nav slot positions).

### Storage layer (`storage/`)

`Storage` is the interface; `AbstractJdbcStorage` implements all shared SQL/serialization logic via
HikariCP, and `SqliteStorage`/`MysqlStorage` only supply the `DataSource` and the two dialect-specific
upsert statements (`INSERT OR REPLACE` vs `ON DUPLICATE KEY UPDATE`). Three tables:

- `ec_pages (uuid, page, data)` — one row per player per page. `data` is a Gson `JsonArray` of 45
  slots, each either `null` or a base64-encoded `ItemStack#serializeAsBytes()`.
- `ec_extra (uuid, extra)` — purchased pages beyond permission-based pages (see below).
- `ec_migrated (uuid, type)` — presence of a row means that `type` (`"vanilla"` / `"axvaults"`) has
  already been migrated for that player; migration is one-shot and idempotent.

Page count is two-layered: `ConfigManager.getBasePages(player)` scans `venderchest.pages.N`
permissions top-down, and `getMaxPages(player)` adds `Storage.getExtraPages(uuid)` on top, capped at
the global `max-pages` config value.

### GUI layer (`gui/`)

`GuiManager` is a per-player session state machine (`Map<UUID, OpenSession>` in `model/OpenSession`).
Opening/closing/navigating a page always loads/saves asynchronously off the main thread, so the code
has to guard against races:

- **`openSeq`**: an increasing counter per player. Async page loads capture their sequence number
  before dispatching; if the player has navigated again before the load's main-thread callback runs,
  the stale callback is dropped (`openSeq.get(uuid) != seq`).
- **`writeBuffer`**: when a page closes/navigates away, the in-memory snapshot is written to
  `writeBuffer` synchronously and the DB write is scheduled async. A near-simultaneous reopen of the
  same page reads `writeBuffer` instead of the DB, since the DB write may not have landed yet — this is
  what prevents item duplication on fast reopen.
- `OpenSession.dirty` gates every save (`saveAllDirty`, `flushAsync`, `closeAll`) so untouched pages
  are never rewritten.

`EnderchestGui`/`MainMenuGui` build `Inventory` objects from `gui/enderchest.yml` / `gui/main.yml`
(slots, materials, MiniMessage names/lore). The content area is slots 0–44; the nav row is the fixed
slot set `EnderchestGui.NAV_SLOTS` (45–53, configurable per-slot within that row). `GuiListener` is
what actually enforces the GUI rules at the event level (blocking shift-clicks into the nav row,
blacklisted items, read-only admin views, double-click COLLECT_TO_CURSOR, drag events touching the top
inventory, etc.) — most GUI bugs will trace back to a missed case here rather than in the builders.

Admin sessions (`GuiManager.openPageAdmin`) reuse the same `OpenSession`/`GuiListener` machinery with
`adminView=true` and an explicit `targetUuid`, so saves are redirected to the target player instead of
the admin viewing them; `readOnly` additionally blocks all content-area mutation.

### Migration (`migration/`)

`MigrationManager` runs once per player on join (`PlayerJoinListener`, 1-tick delay to capture vanilla
ender chest contents on the main thread, then hands off to an async task). It runs two independent,
idempotent migrators, in priority order:

1. `AxVaultsMigrator` (only if `plugins/vEnderchest/import/axvaults.mv.db` exists) — opens that H2
   database read-only (tries several JDBC URL modes since the source file may be locked/versioned
   differently), reads every vault's raw byte blob per player from `axvaults_data`, and manually parses
   the length-prefixed `ItemStack` serialization format inside `deserializeAll`.
2. `VanillaMigrator` — migrates the contents of the player's actual vanilla ender chest.

Both feed into `ShulkerBoxHelper`: existing shulker boxes are kept as-is, everything else is packed
27-per-box via `ShulkerBoxHelper.packAll`, then `ShulkerBoxHelper.placeItems` distributes the resulting
boxes across the player's available pages, logging (and permanently dropping) anything that doesn't fit
once all pages are full. Each migrator marks itself done in `ec_migrated` even when there was nothing to
migrate. `/ecadmin migrate status|reset <player> [vanilla|axvaults]` inspects/clears these flags to force
a re-migration.

The `example/` directory is a checked-in AxVaults test fixture (H2 database files and a local Maven
repo snapshot) used as reference data for that migration path — it is not part of the plugin build.

### Config (`config/ConfigManager.java`)

Loads/reloads `config.yml`, `messages.yml`, `sounds.yml`, `gui/main.yml`, `gui/enderchest.yml` from the
plugin data folder (default resources are copied out on first run via `saveResource`). All player-facing
text goes through MiniMessage (`config.msg(key, resolvers...)` for prefixed messages, `parse()` for GUI
item names/lore, forcing `italic=false` since Minecraft item names default to italic).

## Dependency shading (`build.gradle.kts`)

`shadowJar` relocates `org.sqlite`, `com.mysql`, and `com.zaxxer.hikari` under
`com.valerin.venderchest.libs.*` to avoid classpath collisions with other plugins, but deliberately does
**not** relocate H2 — H2 loads internal resources from string-constant paths (`org/h2/res/**`) that
Shadow's relocation can't patch, so relocating it breaks the classloader at runtime. Native SQLite
binaries are pruned to Linux x86_64 and Windows x86_64 only to keep the jar small.
