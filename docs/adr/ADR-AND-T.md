# ADR-AND-T: V2.2 Annotations — single-device first; FLAG_SECURE on reader

**Status:** Amended (see §"2026-05-23 V2.2.2 amendment" below)
**Date:** 2026-05-22 (original) / 2026-05-23 (V2.2.2 amendment)
**Sprint:** V2 launch (post-Phase-1)

## Context

V2.2 ships user-authored annotations on EPUB content. Highlights are the
MVP scope; notes are reserved for a follow-up (the `Annotation.body`
column is nullable for the V2.2.1 expansion). Multi-device sync is
deliberately out of scope — gated on V2.1 cloud sync per
`docs/plans/v2-scope.md §V2.2 Annotations`.

V1's stance (`ADR-AND-R`) was that FLAG_SECURE is deliberately absent;
screenshots are permitted for accessibility tooling. V2.2 fires
`ADR-AND-R` reconsideration trigger 1 (private user annotation distinct
from third-party EPUB body text), so the reader Activity gets
FLAG_SECURE while annotation content is visible. The infrastructure
shipped earlier (`WindowSecurityController`) is the substrate.

`tts_noAnnotationFeatureInV1` (GroupEFSecurityTest) is the V1 fail-closed
test that this ADR retires per `docs/plans/v2-scope.md` Convention 3
(test-gate replacement, not relaxation).

## Decision

### Storage

A new Room entity `Annotation` lives in `app/src/main/kotlin/com/straysouth/lectern/data/db/`:

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PRIMARY KEY | UUID; deterministic if needed for sync (V2.1) but not required for V2.2 |
| `bookId` | TEXT NOT NULL | FK to `books(id)` with `ON DELETE CASCADE` |
| `locatorJson` | TEXT NOT NULL | `Locator.toJSON().toString()` — NEVER string-interpolated (ADR-AND-N §8) |
| `type` | TEXT NOT NULL | `"highlight"` only in V2.2 MVP; reserved for `"note"`, `"underline"` etc. |
| `createdAt` | INTEGER NOT NULL | Unix millis |
| `body` | TEXT NULL | User note text; `null` for plain highlights |

Indexed on `bookId` for the per-book read query.

Schema migration v2 → v3 lands in `AppDatabase.MIGRATION_2_3`. The
JSON export at `app/schemas/com.straysouth.lectern.data.db.AppDatabase/3.json`
is committed (`exportSchema = true`); the migration test (Sprint 5's
`MigrationTest`) gets a new case for 2→3.

### Privacy & FLAG_SECURE

When at least one annotation surface is in composition, the reader
Activity has `FLAG_SECURE` set via the reference-counted
`WindowSecurityController` (PR #12, foundation). Cleared when the
surface leaves composition.

This is the **first V2 feature** to wire `SecureWindow()` into the UI
tree; future features (V2.8 DRM, V2.9 foreground service, V2.1 auth
screen) reuse the same controller.

### Surface

Compose UI:
1. Selection toolbar — appears when the user selects text in the EPUB
   WebView. Floating toolbar via the Readium selection API
   (`EpubNavigatorFragment.Listener.onSelectionChanged`).
2. Highlight action — creates an `Annotation(type = "highlight")` and
   applies a Readium decoration to render the highlight.
3. Annotation list panel — displays all annotations for the open book
   via `AnnotationDao.observeForBook(bookId)`.

The decoration uses the same `applyDecorations` API already in use for
TTS word highlights and the Focus Band (`ADR-AND-L`). Each annotation
gets its own decoration group so they don't collide with TTS/Focus Band.

### Test gate replacement

Removed: `GroupEFSecurityTest.tts_noAnnotationFeatureInV1`.

Added: `GroupCSecurityTest.schemaV3_annotationsTable_isRegistered` —
asserts the v3 schema includes the new table, CASCADE delete, and the
`bookId` index; pinned via the committed JSON export.

Added: `GroupCSecurityTest.schemaV3_identityHash_isStable` — asserts
the v3 schema identity hash matches the KSP-generated value.

Deferred to V2.2.1: an instrumented `MigrationTestHelper` test that
runs `MIGRATION_2_3` against a real SQLite v2 database. The JVM
schema-export tests above guard the structure; runtime migration
verification needs Android instrumentation and is tracked as the
companion test PR.

## Pinned by

| Guarantee | Test |
|---|---|
| Schema v3 identity hash matches Room-generated value | `GroupCSecurityTest.schemaV3_identityHash_isStable` |
| Schema v3 registers the `annotations` table with CASCADE delete and `bookId` index | `GroupCSecurityTest.schemaV3_annotationsTable_isRegistered` |
| Locator serialised via `toJSON` not interpolation | `GroupASecurityTest.epub_locatorSerialization_usesToJson_notStringInterpolation` (ADR-AND-N pin, unchanged) |
| Reader Activity acquires FLAG_SECURE when an annotation surface is in composition | Source assertion (V2.2.1 follow-up): the annotations Composable calls `SecureWindow()` |
| `WindowSecurityController` is the sole FLAG_SECURE writer | `GroupHSecurityTest.platform_flagSecureAbsent_screenshotsPermitted` (updated to exempt the controller file in PR #12) |

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/data/db/Annotation.kt`
- `app/src/main/kotlin/com/straysouth/lectern/data/db/AnnotationDao.kt`
- `app/src/main/kotlin/com/straysouth/lectern/data/db/AppDatabase.kt` (`MIGRATION_2_3`)
- `app/src/main/kotlin/com/straysouth/lectern/ui/window/WindowSecurityController.kt`
- `app/schemas/com.straysouth.lectern.data.db.AppDatabase/3.json` (committed)
- `docs/adr/ADR-AND-R.md` — will receive a `Status: Amended` section (per
  v2-scope.md Convention 1(c)) when the UI surface lands with `SecureWindow()`
  actually invoked.

## Cross-branch / forward refs

- `ADR-AND-S+` (cloud sync, V2.1) — multi-device annotation sync depends
  on this; until V2.1 ships, annotations are device-local.
- `docs/plans/v2-scope.md §V2.2` — the planning record. This ADR is the
  shipping record.

## Consequences

- The annotation surface is the first V2 feature to wire FLAG_SECURE in
  production. Subsequent V2 features (V2.8 DRM, V2.9 foreground service)
  reuse `SecureWindow()` without re-implementing the controller logic.
- Schema migration discipline is exercised again (first time since
  Sprint 5). `ADR-AND-C` §Consequences holds — the migration test is
  the enforcement.
- `tts_noAnnotationFeatureInV1` is removed, not `@Ignore`d. Reintroducing
  the V1 fail-closed assertion would block V2.2.
- Bookkeeping: when a book is deleted from the library, the FK CASCADE
  removes its annotations. The Delete-Book confirmation dialog should
  mention this — copy update is in the UI follow-up (V2.2.1).

## Known gap

This ADR documents the design + storage + FLAG_SECURE wiring. The
Compose UI surface (selection toolbar, highlight rendering, annotation
list panel) lands in a follow-up PR (V2.2.1) — too much surface to
review in a single PR. The current commit ships:

- Storage (entity, DAO, migration, schema export)
- ADR-AND-T (this file)
- Test-gate replacement (removed `tts_noAnnotationFeatureInV1`)
- Foundation: `WindowSecurityController` already on main (PR #12)

The UI surface follow-up brings:
- `SecureWindow()` invoked in the reader Composable when annotations are
  active
- Selection toolbar Composable
- Annotation Readium decoration group
- Annotation list panel
- `ADR-AND-R` Status:Amended section (per Convention 1(c)) once
  `SecureWindow()` is actually invoked in production code

## 2026-05-23 V2.2.2 amendment

Per v2-scope.md Convention 1(c) — appended dated section, not edit-in-place
of the §Decision above. V2.2.2 extends the V2.2 annotation surface without
contradicting the original Decision.

**What changed:**

- **Notes** (text body on annotations). `Annotation.body` was always nullable
  in the v3 schema; V2.2.1 wrote `body = null` (highlights only). V2.2.2
  writes `body = <user text>` for the new `TYPE_NOTE` type. No migration —
  the column already exists.
- **`AnnotationRepository.TYPE_NOTE`** constant added alongside `TYPE_HIGHLIGHT`.
  `createNote(bookId, locator, body)` is the new write path; `createHighlight`
  is unchanged.
- **Annotation list panel** (Compose `ModalBottomSheet` in
  `AnnotationListPanel.kt`). Lists all annotations for the open book; tap row
  → navigate via `viewModel.requestNavigation(locator)`; delete via `IconButton`
  with parent-surfaced Snackbar undo (AuDHD G.3, deferred to V2.2.3 actual
  Snackbar wiring).
- **Note-entry dialog** (`NoteEntryDialog.kt`). Opened when the user taps the
  toolbar "Note" button with active selection. VM holds `pendingNoteLocator`
  StateFlow so the dialog survives configuration changes.
- **Two new toolbar IconButtons**: `Icons.AutoMirrored.Filled.NoteAdd` and
  `FormatListBulleted`.

**What stayed the same:**

- §Decision (storage, FLAG_SECURE wiring, schema migration) unchanged.
- `tts_doesNotReadAnnotationBodyText` source assertion still holds — the
  Repository indirection means `AnnotationDao` still doesn't reach the VM's
  import set; the new `createNote` / `pendingNoteLocator` / `requestNavigation`
  methods route through `AnnotationRepository`, not the DAO.
- `body` column is opaque user content. The AuDHD banned-token CI gate scans
  app strings (`res/values/*.xml`) and main `.kt` sources, not Room rows;
  user-authored text is out of scope of that lint.

**Test additions:**

- `GroupGSecurityTest.audhd_annotationPanel_existsInReader` — pins the
  `AnnotationListPanel` Composable is invoked from `ReaderOverlay`.
- `GroupEFSecurityTest.tts_doesNotReadAnnotationBodyText` — re-verified;
  source-asserts the VM still does NOT import `AnnotationDao` after V2.2.2.
- Source assertion on the toolbar wiring: both "Note" and "Annotations list"
  IconButtons are present in `ReaderOverlay.kt`.

**Open / V2.2.3 candidates:**

- Per-row delete confirmation Snackbar with Undo affordance (currently
  delete-on-tap; partner Snackbar pending).
- Cross-book annotation panel (Library-level "all my notes").
- Annotation export (JSON / CFI / custom) — likely co-ships with V2.1 cloud
  sync per v2-scope.md §V2.2 open questions.
- Distinct decoration tint for notes vs highlights (currently both render
  with `ANNOTATION_HIGHLIGHT_TINT`).
