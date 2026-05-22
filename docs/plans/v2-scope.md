# V2 Scope Plan — Lectern Android

**Status:** Draft (Sprint 27)
**Date:** 2026-05-17
**Audience:** future contributors evaluating any of the 9 deferred V2 items.

## Purpose

Scope the 9 V2 features tracked in `memory-bank/06-progress.md §Deferred (V2+)`.
Reserve ADR slots, map affected V1 ADRs and test gates, surface
dependencies and FLAG_SECURE consequences. This is a **scoping doc**, not
an implementation plan — each feature ships behind its own ADR.

## Non-goals

- Sprint allocation or timelines.
- Implementation code, schema designs, or UI specs.
- Decisions on feature ordering — only dependency facts.
- Focus Band V2 (line-level highlight). Per `ADR-AND-L`, that is **V3**,
  not V2 — gated on a future native Compose text surface that doesn't
  exist yet.

## Conventions established by this doc

1. **One new ADR per V2 feature that introduces a new constraint,
   permission, network surface, or reverses a fail-closed V1 test.**
   Substantive Decision-section changes to V1 ADRs are forbidden —
   ship a new `ADR-AND-S+` instead. **Narrow amendments to V1 ADRs
   are allowed in three cases and three only:**
   (a) `Status:` field transitions (e.g. `Deferred` → `Accepted`
       when a previously-stub ADR like `ADR-AND-H` is converted —
       the new substantive content lives in the new V2 ADR; the
       V1 file's Status flip just records the closure);
   (b) forward-reference updates (e.g. resolving `ADR-AND-S+` once
       the actual slot lands, per Convention #2);
   (c) `Status: Amended` with a new dated section appended below
       the original — never an edit-in-place of historical text.
       `ADR-AND-L` already follows this pattern (V1→V3 deferral
       added as an amendment section, not by editing the
       original). The risk-register entries that mention
       "amendment when it lands" refer specifically to this case.
   Features that don't introduce a new constraint, permission,
   network surface, or fail-closed reversal may ship with a
   DEVLOG entry only (per-card "ADR needed?" answers below
   indicate which features fall in each bucket).
2. **ADR slot reservations are reservable, not committed.** The
   letter assignments below indicate intent; if the implementation
   order differs, slots reassign in the order they are actually
   filed. The reservation matters only for forward references in
   existing ADRs (currently: `ADR-AND-I` and `ADR-AND-J` both point at
   "ADR-AND-S+" for cloud sync — so if cloud-sync is *not* the first
   V2 ADR filed, this doc must be updated to keep those forward refs
   accurate).
3. **Test gate replacement, not relaxation.** Several V1 tests are
   named with `_inV1` or `_noXFeature` suffixes that fail-close the
   feature. When the feature ships, the V1 test is *replaced* by a
   different test asserting the new constraint (e.g. "annotations
   are encrypted-at-rest" replaces "no annotations"). The replaced
   test is removed in the V2 feature's PR; do not leave the V1 test
   as `@Ignore`.
4. **Cross-branch readability.** This doc cites tests on the Track C
   stack and ADRs on Track A. Pre-merge order for it to read clean
   on trunk: Track A → Track C stack → this doc's branch.

## ADR slot reservations

| Slot | Intended scope | Forward-ref source |
|---|---|---|
| `ADR-AND-S` | Cloud sync architecture (Supabase) | `ADR-AND-I` §Consequences, `ADR-AND-J` §Consequences |
| `ADR-AND-T` | Annotations (highlights / notes) | (no existing forward-ref) |
| `ADR-AND-U` | STT captions | (no existing forward-ref) |
| `ADR-AND-V` | Readium LCP / DRM adapter | (no existing forward-ref) |
| `ADR-AND-W` | Foreground service | `ADR-AND-R` §V2 reconsideration triggers (item 5) |

Three V2 features — RSVP reader, retrieval/spaced-repetition, A11y V2 —
may not need standalone ADRs; the scope cards below state the test for
"does this need an ADR?" for each.

## Feature scope cards

Card template: Summary · iOS parity · New surfaces · V1 ADRs in play ·
V1 tests affected · FLAG_SECURE triggers · Dependencies · ADR needed ·
T-shirt size · Open questions.

---

### V2.1 — Cloud sync (Supabase)

**Summary.** Bidirectional sync of books, locators, anchors, and
typography preferences across devices for the same user. Calibration
weights remain device-local (`ADR-AND-J` §Consequences).

**iOS parity.** iOS ADR-I forbids LLM in V1 / V1.1; sync is not the
same surface. No direct iOS parity ADR — this is an Android-first V2
move. iOS counterpart may or may not exist.

**New surfaces.** Auth screen (credentials), settings sync toggle,
conflict-resolution UI for divergent reading positions.

**V1 ADRs in play.**
- `ADR-AND-I` — fully reverses §3 "no analytics/telemetry/crash-reporting"
  in spirit; sync metadata IS a telemetry-shaped surface and needs an
  explicit consent gate. The Decision §3 banlist (Firebase Analytics,
  Crashlytics, Mixpanel, Amplitude, Segment, Bugsnag, Datadog) remains.
- `ADR-AND-J` — calibration weights MUST NOT sync. New ADR must
  reiterate.
- `ADR-AND-N` — `BlockingHttpClient` remains the only Readium HTTP
  client; sync uses a separate non-Readium HTTP path.

**V1 tests affected.**
- `GroupHSecurityTest.platform_internetPermission_noActualNetworkCalls_inMainSources` —
  must be replaced (sync introduces legitimate network calls).
- `GroupASecurityTest.epub_publicationRepository_blockingHttpClient_noDefaultHttpClient` —
  unchanged (Readium path stays blocked).
- `GroupBSecurityTest.bookCacheId_*` — unchanged; deterministic UUID
  becomes the cross-device sync key.

**FLAG_SECURE triggers.**
- Login / authentication screen → `ADR-AND-R` trigger 2. FLAG_SECURE
  on the auth Activity.

**Dependencies.** None upstream. Downstream: gates multi-device
annotation sync (V2.2), retrieval sync (V2.3).

**ADR needed.** Yes — `ADR-AND-S`. Must include: threat model, consent
surface (granular per data type), encryption-at-rest expectations,
conflict-resolution policy, calibration-weights-stay-local clause.

**T-shirt size.** XL.

**Open questions.**
- Server-side schema lives where (Supabase repo, separate)?
- E2E encryption vs server-readable?
- Account model: device-only, email/password, OAuth?

---

### V2.2 — Annotations (highlights / notes)

**Summary.** User-authored highlights and text-anchored notes on EPUB
content. Local-first; syncs via V2.1 if enabled.

**iOS parity.** iOS ADR-T (annotations) is the likely parity slot.

**New surfaces.** Selection toolbar in reader, notes pane, persisted
annotation overlay on the Readium WebView (Readium decoration API).

**V1 ADRs in play.**
- `ADR-AND-R` — annotations are user-authored content distinct from
  third-party EPUB text → trigger 1 fires; FLAG_SECURE on the reader
  Activity becomes required.
- `ADR-AND-J` — annotations are NOT gaze-derived; outside the gaze
  ephemerality scope.

**V1 tests affected.**
- `GroupEFSecurityTest.tts_noAnnotationFeatureInV1` — *replaced* by
  positive assertions: annotations persist via Room with a documented
  schema, annotation export is intentional (or absent).

**FLAG_SECURE triggers.**
- Private user annotation → `ADR-AND-R` trigger 1. FLAG_SECURE on the
  reader Activity (the entire Activity, not just the annotation
  surface — annotations decorate body text).

**Dependencies.** None upstream for local-only annotations. Multi-device
sync depends on V2.1.

**ADR needed.** Yes — `ADR-AND-T`. Schema, FLAG_SECURE consequence,
test-gate replacement, sync interaction with V2.1.

**T-shirt size.** L.

**Open questions.**
- Annotation export format: JSON, EPUB CFI, custom?
- Note text searchable across library, or per-book only?

---

### V2.3 — Retrieval / spaced repetition

**Summary.** Surface user-flagged passages (likely annotations or a
new lightweight "saved" flag) on a spaced-repetition cadence.
AuDHD-friendly: opt-in, no streaks, no urgency copy.

**iOS parity.** iOS has nothing equivalent on file in the surfaced
ADRs.

**New surfaces.** Review queue screen, possibly a daily-suggestion
notification (which would drag in foreground-service-adjacent
behaviour — see Open questions).

**V1 ADRs in play.**
- `RULES.md §AuDHD copy` — review-queue copy must clear the banned-token
  list (no streak, consecutive, daily goal, etc.).
- `ADR-AND-K` (Track A backfill — "no gamification" iOS-mirror) —
  reinforces the AuDHD-copy constraint.

**V1 tests affected.** None at the platform level. New tests will
verify the review-queue copy passes `check_banned_strings.sh`.

**FLAG_SECURE triggers.** None directly. If a review notification
ships (see Open Questions), notification content rendering must not
echo annotation text — push that decision to `ADR-AND-T` (annotations)
or this feature's ADR.

**Dependencies.** Annotations (V2.2) — retrieval needs *something* to
schedule. If retrieval ships on bare "saved bookmarks" without
annotations, that dependency relaxes.

**ADR needed.** Probably no — if no new permissions and no new
fail-closed reversals, document in DEVLOG. Yes if a notification
surface (PendingIntent) is introduced — then it needs its own ADR
to replace `platform_noPendingIntent_inMainSources`.

**T-shirt size.** M (without notifications), L (with).

**Open questions.**
- Notification-based reminder or in-app only?
- Scheduling algorithm: FSRS, SM-2, custom, none-just-recency?

---

### V2.4 — RSVP reader

**Summary.** Rapid-serial-visual-presentation reader surface for
plain-text content, alternative to the EPUB paginated reader. One
word at a time at a fixed cadence.

**iOS parity.** iOS ADR (TBD letter) — unknown. RSVP is a recognised
reading pattern, no Android-specific concern.

**New surfaces.** New reader Composable, settings (WPM, font),
possibly a "send-to-RSVP" affordance from the EPUB reader.

**V1 ADRs in play.**
- `ADR-AND-O` — rendering-surface isolation. RSVP is a Compose body
  surface, fully compliant with the rule (Compose for chrome AND
  body; WebView only for EPUB via ADR-AND-N).
- `ADR-AND-L` — RSVP is the first native Compose text surface that
  *could* support Focus Band V2 (line-level), but RSVP is one-word
  display so the line-level path is moot. Note this in `ADR-AND-L`
  amendment if RSVP ships before the line-level reader.

**V1 tests affected.** None.

**FLAG_SECURE triggers.** None.

**Dependencies.** None.

**ADR needed.** Optional — if RSVP plugs into existing Readium content
extraction without architectural change, DEVLOG entry suffices.
Required if RSVP introduces a new content-extraction path (e.g.
external `.txt` ingest separate from Readium).

**T-shirt size.** M.

**Open questions.**
- Source: only EPUB body text, or external `.txt` / clipboard too?
- WPM range and pause-on-punctuation policy?

---

### V2.5 — F5-class envelope-consumer features

**TBD — needs scope from owner.**

The only reference to "F5-class envelope-consumer" in this repo is
`06-progress.md:140-141`:

> - F5-class envelope-consumer features (paragraph tint, focus vignette,
>   gaze-TTS soft pause)

No iOS-side ADR, no design doc, no `F5` definition anywhere else in
the repo. The three named sub-features (paragraph tint, focus vignette,
gaze-TTS soft pause) all *sound* like consumers of the existing
`GazeProvider` output, but their scope cannot be confidently mapped
without owner input.

**Questions blocking scope:**

1. What does **F5** stand for / refer to? Is it an iOS-side
   ADR-AND-{F5}, an iOS feature class, an internal codename, or
   external terminology (e.g. an HCI-research term)?
2. **Paragraph tint** — gaze-driven (tint the paragraph the user is
   reading) or stable (one-time visual segmentation)? If
   gaze-driven, requires paragraph bounding rects → same
   constraint that defers Focus Band V2 to V3 per `ADR-AND-L`.
3. **Focus vignette** — pixel-overlay (like V1 Focus Band, no text
   awareness) or text-aware? If pixel-overlay only, it slots into
   the existing `FocusBandPrefs` mechanism and may not need a new
   ADR. If text-aware, this is V3 (same Compose-text-surface gate).
4. **Gaze-TTS soft pause** — is the trigger "user looks away" (gaze
   leaves screen bounds) or "user re-reads a sentence" (regression
   detection)? Re-read detection requires a per-sentence dwell
   estimator, currently not in `GazeProvider`.

**Provisional ADR letter:** none reserved until scope is clear.

**Provisional T-shirt size:** unknown — anywhere from S (one
pixel-overlay tweak) to XL (new gaze-classification pipeline).

---

### V2.6 — A11y V2 (chapter rotor with heading levels)

**Summary.** TalkBack-driven chapter rotor exposing EPUB heading
hierarchy (H1, H2, H3) for navigation, mirroring iOS VoiceOver rotor.

**iOS parity.** iOS A11y ADR (letter unknown) — likely a direct
parity feature.

**New surfaces.** No new screen; this is a `semantics {}` enrichment
on the reader Composable plus a Readium TOC traversal.

**V1 ADRs in play.**
- `RULES.md §Accessibility` — every `@Composable` screen has a
  `semantics {}` block (already enforced). V2 extends with
  heading-level semantics.
- `ADR-AND-O` — rendering-surface isolation unaffected; rotor lives
  in Compose chrome, body stays in WebView.

**V1 tests affected.** Possibly new tests asserting heading-level
semantics presence; nothing replaced.

**FLAG_SECURE triggers.** None.

**Dependencies.** None.

**ADR needed.** Optional. A11y enhancements are continuous; an ADR is
warranted only if a deliberate trade-off is being recorded
(e.g. "we don't expose footnote rotor because Readium API X").

**T-shirt size.** M.

**Open questions.**
- Readium API surface for TOC traversal — confirmed sufficient, or
  workaround needed?
- Footnote / image / link rotors in scope, or chapter-only?

---

### V2.7 — STT captions

**Summary.** Live transcription of TTS output rendered as captions,
plus optional user voice-control commands.

**iOS parity.** iOS ADR-H requires STT fail-closed. `ADR-AND-H`
mirrors as Deferred for V1.

**New surfaces.** Caption strip overlay in reader, settings toggle,
possibly voice-command grammar.

**V1 ADRs in play.**
- `ADR-AND-H` (Deferred) — per Convention 1(a), its `Status:` field
  flips from `Deferred` to `Accepted` when STT ships; the substantive
  content (fail-closed policy, RECORD_AUDIO handling) lives in the
  new `ADR-AND-U`, not in an edit to `ADR-AND-H`.
- `RULES.md §Privacy` — RECORD_AUDIO is a permission gate that
  privacy-conscious users must consent to. Manifest declares;
  runtime request before first use; user can revoke.

**V1 tests affected.**
- `GroupEFSecurityTest.tts_noMicrophonePermissionRequested` —
  *replaced* by tests that assert: RECORD_AUDIO IS in manifest;
  runtime permission flow exists; STT engine is on-device only;
  no audio bytes leave the device.

**FLAG_SECURE triggers.** None directly. Caption text *is* TTS body
text — already visible, no new exposure.

**Dependencies.** None.

**ADR needed.** Yes — `ADR-AND-U`. Permission policy, on-device
engine selection, fail-closed verification, log-leak audit (the same
audit pattern as `GroupDSecurityTest.gazeProviderImpl_logLines_*`).

**T-shirt size.** L.

**Open questions.**
- Engine: Android `SpeechRecognizer` (Google), Whisper-on-device, both?
- Cap caption history — privacy posture says don't persist
  transcripts; UX says scrollback is useful.

---

### V2.8 — DRM / Readium LCP adapter

**Summary.** Support for Readium LCP-encrypted EPUB / PDF publications.

**iOS parity.** iOS likely already has an LCP track or has explicitly
deferred. Letter unknown.

**New surfaces.** License acquisition / passphrase entry UI.

**V1 ADRs in play.**
- `ADR-AND-N` — `BlockingHttpClient` is the only Readium HTTP client.
  LCP license acquisition is a Readium network call → must route
  through a *new* permitted-endpoint client, or LCP server allowlist
  added to `BlockingHttpClient`. New ADR must specify.
- `ADR-AND-R` — encrypted-content reading surface → trigger 3 fires.
  FLAG_SECURE on the reader Activity when an LCP publication is open.

**V1 tests affected.**
- `GroupASecurityTest.epub_publicationRepository_blockingHttpClient_noDefaultHttpClient` —
  must be updated to permit the LCP license client (still no default
  HTTP client; an explicit named LCP client is allowed).

**FLAG_SECURE triggers.**
- Encrypted-content reading surface → `ADR-AND-R` trigger 3.

**Dependencies.** None upstream. Cloud sync (V2.1) is independent —
licenses can be device-local or sync-enabled, both viable.

**ADR needed.** Yes — `ADR-AND-V`. Network policy, FLAG_SECURE
consequence, license-storage location (cleartext disallowed),
key-derivation expectations.

**T-shirt size.** L.

**Open questions.**
- LCP server endpoints: configurable per-publication or app-wide
  allowlist?
- Returnable publications (loans) — supported on Android side?

---

### V2.9 — Foreground service

**Summary.** A foreground service for long-running operations —
specifically TTS playback continuation when the app is backgrounded.

**iOS parity.** Background audio is a different platform model on
iOS; no direct ADR parity expected.

**New surfaces.** Notification (persistent, dismissible only by
stopping TTS). Service class with proper lifecycle.

**V1 ADRs in play.**
- `ADR-AND-R` — trigger 5 fires: foreground service with persistent
  notification. FLAG_SECURE consequence depends on notification
  content. If the notification echoes the book title or reading
  position, screenshot-capture threat surface widens.
- `ADR-AND-A` — `AudioSessionCoordinator` is the sole audio-focus
  owner. The foreground service must delegate to it, not duplicate
  focus management.

**V1 tests affected.**
- `GroupHSecurityTest.platform_noPendingIntent_inMainSources` —
  *replaced* by positive assertion: PendingIntents exist only with
  `FLAG_IMMUTABLE`, only in the foreground-service module.
- `GroupHSecurityTest.platform_onlyMainActivityIsExported` —
  unchanged (services are not exported activities).
- `check_audio_session.sh` — must continue to pass; the foreground
  service is a coordinator delegate, not a new audio-focus owner.

**FLAG_SECURE triggers.**
- Foreground service with persistent notification → `ADR-AND-R`
  trigger 5. The notification surface itself is screenshot-capturable
  from the shade.

**Dependencies.** None.

**ADR needed.** Yes — `ADR-AND-W`. Notification content policy
(does it show book title? gaze indicator? reading position?),
lifecycle, audio-focus delegation to `AudioSessionCoordinator`,
PendingIntent immutability requirement.

**T-shirt size.** L.

**Open questions.**
- Is the foreground service for TTS only, or also a planned
  retrieval/notification surface (V2.3)?
- Notification UI: minimal (icon + "Reading") or rich (title +
  progress)?

## Dependency graph

```
                 ┌─────────────────────────┐
                 │ V2.1 Cloud sync (S, XL) │
                 └────┬────────────────────┘
                      │   gates multi-device sync of:
                      ├──→  V2.2 Annotations  (T, L)
                      └──→  V2.3 Retrieval   (?, M-L)
                                  │
                                  └─ depends on something to schedule;
                                     typically V2.2 annotations.

  Independent (no V2 prerequisite):
  - V2.4 RSVP reader      (?, M)
  - V2.5 F5 *TBD*         (?, ?)
  - V2.6 A11y V2          (?, M)
  - V2.7 STT captions     (U, L)
  - V2.8 DRM / LCP        (V, L)
  - V2.9 Foreground svc   (W, L)
```

## Cross-cutting risk register

| Risk | Affected items | Mitigation surface |
|---|---|---|
| 4 features trigger FLAG_SECURE re-enable; first trigger to ship pays the integration cost | V2.1, V2.2, V2.8, V2.9 | Whichever V2 feature ships first writes a small helper that the other three reuse. Because Lectern is single-Activity Compose, the helper MUST support both enabling AND disabling — a one-way `enableFlagSecureOnActivity()` would leak FLAG_SECURE onto non-sensitive screens (Settings, Library) when the user navigates away from a sensitive Composable. A naive per-Composable `DisposableEffect` is **not safe either**: when navigating between two sensitive Composables, `onDispose` of the outgoing screen can run after composition of the incoming screen, clearing the flag while a sensitive screen is still visible. Use a **reference-counted centralized state** (e.g. a `WindowSecurityController` exposed via `CompositionLocalProvider` or a hoisted ViewModel) where each sensitive Composable increments on entry and decrements on disposal; FLAG_SECURE is set whenever the counter > 0 and cleared at 0. Document the chosen surface in the `ADR-AND-R` amendment when it lands. |
| Test-gate replacement (not relaxation) is a discipline; an `@Ignore` left behind is silent regression | V2.2, V2.7, V2.9 | Pre-merge checklist on each V2 ADR's PR: "the V1 fail-closed test is *removed*, not @Ignored. Replacement test exists in the same Group file." |
| ADR forward-reference drift: `ADR-AND-I` and `ADR-AND-J` both say "ADR-AND-S+ cloud sync". If a different V2 ships first and claims S, those forward refs go stale. | V2.1 vs anything-shipping-first | If the order changes, the first-shipping V2 ADR's PR must update `ADR-AND-I` and `ADR-AND-J` forward references atomically. |
| New permissions (RECORD_AUDIO for V2.7) reset Play Store policy disclosures | V2.7 | Store-listing review is a Sprint deliverable in V2.7's PR, not an afterthought. |
| Notification surfaces (V2.3 if notification-based, V2.9) blur the FLAG_SECURE perimeter — shade is screenshot-capturable separately from the app | V2.3, V2.9 | Each ADR explicitly enumerates notification-payload content + the screenshot threat decision. |

## Out of scope (intentional)

- **Focus Band V2 (line-level gaze highlight).** Per `ADR-AND-L`,
  this is **V3** — gated on a future native Compose text surface for
  body rendering that doesn't currently exist. Not addressed here.
- **LLM features.** `ADR-AND-I` Decision §1 forbids in V1; "no LLM, no
  backend, no cloud sync in V1" reads literally. V2.1 cloud sync
  does not implicitly reverse the LLM clause; an explicit ADR would
  be required separately.
- **Multi-user / shared libraries.** Single-user assumption is
  pervasive; broadening it is a V3 conversation, not a V2 ADR slot.

## Maintenance notes

When a V2 ADR is filed:
1. Update §"ADR slot reservations" — strike the reservation, add the
   filed ADR's actual letter.
2. Update the relevant scope card with the filed ADR's filename.
3. Update `memory-bank/06-progress.md §Deferred (V2+)` — remove the
   shipped item.
4. If the filing order differs from the reservation table, audit
   `ADR-AND-I` and `ADR-AND-J` for stale "ADR-AND-S+" forward refs.

When `V2.5 F5-class envelope-consumer` gets scope from the owner:
1. Replace the TBD stub with a real scope card.
2. Add the letter to §"ADR slot reservations" if a new ADR is
   warranted.
3. Update the dependency graph if it consumes V2.1 or V2.2 output.
