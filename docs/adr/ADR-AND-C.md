# ADR-AND-C: Room schema lock policy

**Status:** Accepted
**Date:** 2026-05-16
**Sprint:** 24 (backfill)

## Context

iOS ADR-C governs CloudKit schema policy (append-only, no
`@Attribute(.unique)`, parser CI, round-trip integration test).

Android V1 has no cloud sync. There is no CloudKit-equivalent surface
to govern. However, Room schema integrity is a non-negotiable correctness
property: schema-version drift breaks installed users on upgrade, and
silent migration bugs corrupt the library DB on first launch after a
release.

## Decision

Room schema is locked by the following gates:

1. **Identity-hash pin per schema version.** Each Room schema version
   has a JSON export checked into `app/schemas/`. The identity hash of
   the schema for each version is asserted byte-stable in unit tests.
   Bumping a schema version without a planned migration fails the build.

2. **Migration SQL is asserted explicitly.** For each `n → n+1`
   migration, a unit test asserts the SQL it emits. Adding a column
   without a migration entry fails the build.

3. **No destructive fallback.** `Room.databaseBuilder(...)` must never
   call `fallbackToDestructiveMigration()`. Asserted by unit test.

4. **Schema assets on instrumented tests.** `MigrationTestHelper`
   reads schema JSON from device-side assets, not host-side files;
   `build.gradle.kts` wires `app/schemas/` into the androidTest assets
   source set. Without this wiring, instrumented migration tests throw
   `FileNotFoundException` at runtime.

## Pinned by

| Guarantee | Test (in `app/src/test/kotlin/.../security/`) |
|---|---|
| Schema v1 identity hash byte-stable | `GroupCSecurityTest.schemaV1_identityHash_isStable` |
| Schema v2 identity hash byte-stable | `GroupCSecurityTest.schemaV2_identityHash_isStable` |
| Books table — v2 added `format` column not null | `GroupCSecurityTest.schemaV2_booksTable_hasFormatColumn_notNull` |
| Books table — v1 lacked `format` column | `GroupCSecurityTest.schemaV1_booksTable_hasNoFormatColumn` |
| Migration 1→2 SQL adds format column with EPUB default | `GroupCSecurityTest.migration1to2_sql_addsFormatColumnWithEpubDefault` |
| Database builder does not enable destructive fallback | `GroupCSecurityTest.appDatabase_builderDoesNotCallFallbackToDestructiveMigration` |

Instrumented coverage:
- `app/src/androidTest/kotlin/.../db/RoomMigrationTest.kt`
- `app/src/androidTest/kotlin/.../db/SchemaVersionMismatchTest.kt`

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/data/db/AppDatabase.kt`
- `app/schemas/`
- `app/build.gradle.kts` (`sourceSets.androidTest.assets.srcDirs`)

## Consequences

- Schema bumps require: new JSON export, new identity-hash assertion,
  explicit migration class + SQL assertion, instrumented migration test.
- Adding a Room entity does not auto-generate any of the above —
  this ADR (ADR-AND-C) is itself the rule against silent schema
  additions; the migration test pins enforcement.
- No `@Entity` may use a name matching the gaze denylist
  (`face|eye|gaze|lookAt`) — enforced by `scripts/check_gaze_data_leak.sh`
  (see ADR-AND-J).
