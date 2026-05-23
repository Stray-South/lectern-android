# ADR-AND-X: V2.4 RSVP — content-source policy (EPUB + .txt + clipboard)

**Status:** Accepted
**Date:** 2026-05-23
**Sprint:** V2 launch (post-V2.2 series)

## Context

V2.4 ships a Rapid-Serial-Visual-Presentation reader for plain text. Per
`docs/plans/v2-scope.md §V2.4`, RSVP is a Compose body surface (ADR-AND-O
compliant). The owner has chosen three content sources: EPUB body text,
SAF-picked `.txt` URIs, and the clipboard. Each source needs a fail-closed
ingest policy because:

1. **EPUB body text** is bounded by Readium's existing publication-open
   pipeline. No new attack surface.
2. **`.txt` ingest** introduces a new content-extraction path outside
   Readium. ADR-AND-N (the BlockingHttpClient guard) does NOT apply
   because `.txt` reads use Android's `ContentResolver`, never HTTP. But
   the file-system surface is new and needs path-traversal protection.
3. **Clipboard ingest** is the most sensitive. Clipboard content is
   user-private (passwords, payment data, intimate messages) and a single
   misdirected log call leaks it.

## Decision

### Source 1 — EPUB body text

- Open the publication via the existing `PublicationRepository` (which
  in turn routes through Readium's `PublicationOpener` per ADR-AND-N).
- Extract plain text via `Publication.content()?.text()` (Readium 3.x
  Content service).
- Close the publication on RSVP screen disposal (`onCleared()`).
- No new code path; no new permissions; no new network surface.

### Source 2 — `.txt` URI

- **Picker:** `ActivityResultContracts.OpenDocument()` with MIME filter
  `text/plain`. SAF returns `content://` URIs only; `file://` URIs are
  never accepted because SAF doesn't produce them in this contract.
- **Read:** `contentResolver.openInputStream(uri)` → `bufferedReader().readText()`.
- **No FileProvider write paths.** The reader never copies the .txt to
  internal storage. The URI is held in memory for the RSVP session
  only.
- **Path-traversal protection:** SAF guarantees the URI is content://
  and access is permission-granted by the user via the system picker.
  We do not parse the URI as a file path; we do not call `File(uri)`.
- **Logging:** the URI is NEVER logged. Read failures log only the
  exception class name (`it.javaClass.simpleName`), never the URI or
  content.

### Source 3 — Clipboard

- **Read:** `LocalClipboardManager.current.getText()?.text` snapshot at
  entry time. The clipboard is not observed; no listener is registered.
- **In-memory only:** the snapshot is held in `RsvpSource.Clipboard.text`
  and the `RsvpUiState.Ready.words` list. **No DataStore write**, **no
  Room write**, **no log write**, **no analytics**.
- **No `rememberSaveable`:** the RSVP nav state in `MainActivity` uses
  plain `remember`. On process death the RSVP session resets to library
  — the correct privacy-preserving behavior for clipboard content.
- **Empty clipboard:** a Snackbar surfaces "Clipboard is empty"; no
  RSVP screen is launched.

## Privacy invariants (pinned)

- The strings `clipboard`, `Clipboard.text`, and the contents of the
  `RsvpSource.Clipboard` data class are NEVER passed to `Log.*`. This
  is enforced by source assertion `GroupGSecurityTest.rsvp_clipboardNeverLogged`.
- The `.txt` URI is NEVER passed to `Log.*`. Same enforcement via
  `GroupGSecurityTest.rsvp_txtUriNeverLogged`.
- The RSVP nav state in `MainActivity` uses `remember { ... }`, NOT
  `rememberSaveable`. Pinned by source assertion
  `GroupGSecurityTest.rsvp_navStateNotSaveable`.

## Test gate additions

- `GroupGSecurityTest.rsvp_clipboardNeverLogged` — source assertion on
  `RsvpViewModel.kt`: no `Log.` call appears on the same line as
  `clipboard`, `source.text`, or `words`.
- `GroupGSecurityTest.rsvp_txtUriNeverLogged` — source assertion: no
  `Log.` call appears on a line containing `uri.path`, `uri.toString`,
  or `source.uri`.
- `GroupGSecurityTest.rsvp_navStateNotSaveable` — source assertion on
  `MainActivity.kt`: `currentRsvpSource` is not `rememberSaveable`.

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/ui/rsvp/RsvpScreen.kt`
- `app/src/main/kotlin/com/straysouth/lectern/ui/rsvp/RsvpViewModel.kt`
- `app/src/main/kotlin/com/straysouth/lectern/ui/rsvp/RsvpSource.kt`
- `app/src/main/kotlin/com/straysouth/lectern/data/repository/PlainTextTokenizer.kt`
- `app/src/main/kotlin/com/straysouth/lectern/data/repository/RsvpPrefs.kt`
- `app/src/main/kotlin/com/straysouth/lectern/data/repository/RsvpRepository.kt`

## Cross-branch / forward refs

- `ADR-AND-N` (Readium HTTP client) — unaffected. `.txt` reads use
  ContentResolver, not HTTP.
- `ADR-AND-O` (rendering-surface isolation) — RSVP is a Compose body
  surface; compliant with the rule that Compose owns chrome AND body
  for non-EPUB content. No WebView.
- `ADR-AND-L` — RSVP is the first native Compose body text surface in
  the app. See `ADR-AND-L` Status: Amended section.
- `ADR-AND-T` (annotations) — unaffected. RSVP is read-only; no
  annotation creation.
- `docs/plans/v2-scope.md §V2.4` — the planning record. This ADR is
  the shipping record.

## Consequences

- A new ingest surface lands in production. The clipboard read is the
  most sensitive; the test gates above are non-negotiable.
- Per-book "Open in RSVP" from Library row long-press is deferred to
  V2.4.1. Current entry points: Library top-bar (clipboard, .txt) and
  EPUB reader toolbar (Switch to RSVP — covers per-book EPUB source).
- RSVP-exit-writes-Locator-back is deferred to V2.4.1. Today, exiting
  RSVP returns to the previous screen without updating the source's
  reading position. This is acceptable for clipboard/.txt (no position
  concept) and degraded-but-functional for EPUB (user re-opens at the
  last saved EPUB position, which lags by the RSVP session length).

## Open / V2.4.1 candidates

- Per-book "Open in RSVP" from Library long-press menu.
- RSVP-exit-writes-Locator: when source is `RsvpSource.Book`, on pause
  / exit write the current word's locator to `LocatorRepository`. EPUB
  resume picks up where RSVP left off.
- Pause-on-punctuation calibration UI (currently a binary toggle;
  could expose per-multiplier sliders).
- `.txt` size cap with a warning Snackbar above N MB.
