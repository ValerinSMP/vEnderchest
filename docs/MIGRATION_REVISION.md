# Migration note: `revision` column

## What changed

`ec_pages` gains one new column:

```sql
ALTER TABLE ec_pages ADD COLUMN revision BIGINT NOT NULL DEFAULT 0
```

This is applied automatically, every time the plugin starts (`AbstractJdbcStorage.init()` /
`MysqlStorage.init()`), on both SQLite and MySQL.

## Why it's safe

- **Additive only.** No existing column, row, or table is touched, dropped, or rewritten. `data`
  (the serialized inventory content) is completely unaffected.
- **Idempotent.** Neither SQLite nor MySQL support `ADD COLUMN IF NOT EXISTS`, so the migration just
  attempts the `ALTER TABLE` on every startup and swallows the resulting `SQLException` on every run
  after the first (it means "column already exists"). There is no version tracking needed and no way
  for this to run twice destructively.
- **Existing rows default to revision 0.** The very first save against a pre-existing page
  transparently becomes the initial compare-and-swap write (`0 → 1`); nothing needs to be
  backfilled, and no existing vault contents are affected by that first write beyond the normal save
  it would have performed anyway.
- **No downtime requirement.** The `ALTER TABLE` runs as part of normal plugin startup — there is no
  separate migration step or maintenance window needed.

## Rollback

Downgrading to a previous build of the plugin (one that doesn't know about `revision`) is safe: the
old code simply never selects or writes that column, so it's ignored. The column can be left in
place indefinitely; it costs one `BIGINT` per row.

## `ec_extra` / `ec_migrated`

Neither table changed. Extra purchased vault pages and migration-tracking flags are unaffected by
this change.

## What was *not* added

No new table (e.g. a cross-server lock table) was introduced by this change — the revision column on
`ec_pages` is the only schema change. See
[`VANTIDUPE_API.md`](VANTIDUPE_API.md) and [`DUPLICATION_FIX.md`](DUPLICATION_FIX.md) for why that's
sufficient on its own to prevent duplication even across two servers sharing one MySQL database.
