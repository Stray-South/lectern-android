# ADR-AND-W: V2.9 TTS foreground service — sentinel + notification owner

**Status:** Accepted
**Date:** 2026-05-25
**Sprint:** V2.9 (background TTS)

## Context

V2.9 introduces background TTS playback for Lectern's read-aloud
surface. The reader had to be foreground for TTS to keep playing in
V1; backgrounding the app stopped speech mid-paragraph (see
`GroupEFSecurityTest.tts_onStop_callsPauseTts` and the E.1 fix it
pins).

A foreground service is the standard Android answer. The architectural
question is **how much of the TTS path the service owns**. Two V1
invariants constrain that choice:

- `ADR-AND-A` — `AudioSessionCoordinator` is the sole owner of
  `AudioManager` focus state for the entire app. The CI gate
  `scripts/check_audio_session.sh` forbids `requestAudioFocus` /
  `abandonAudioFocusRequest` / `AudioFocusRequest.Builder` calls
  outside `AudioSessionCoordinator.kt`.
- `ADR-AND-R` reconsideration triggers item 5 — a foreground service
  with a persistent notification widens the screenshot-capture surface
  because the notification shade is screenshot-capturable separately
  from the app.

## Decision

The V2.9 foreground service is a **sentinel + notification owner**.
`EpubReaderViewModel` remains the sole owner of `_ttsNavigator` and
the binding to `AudioSessionCoordinator`. The service does not call
`AudioManager` directly, does not own the TtsNavigator, and does not
duplicate any audio-focus state — it only:

1. Holds the foreground state so the OS does not kill the process
   while TTS is playing in the background.
2. Owns the persistent media-style notification (book title +
   chapter + play/pause + stop) and the PendingIntents wired into it.
3. Forwards user actions from the notification (play/pause, stop,
   recents swipe) back to the VM via `TtsServiceCallbacks`.

Concrete consequences:

- **Single cleanup path.** Every stop trigger — VM `stopTts()`,
  notification Stop action, recents swipe (`onTaskRemoved`), VM
  `onCleared()` — routes through `viewModel.stopTts()` →
  `cleanUpTts()` → service unbind → `stopService()`. The service does
  not call its own `stopSelf()` in response to user input; the VM
  always decides.

- **Notification build is synchronous-default-then-async-rich.**
  `TtsForegroundService.onCreate()` calls `startForeground(N,
  buildDefault(...))` immediately with a minimal notification
  (`app_name` + "Reading" + play icon). This is ANR-safe and meets
  the foreground-service-start deadline regardless of how long the
  book metadata / TOC lookups take. After the VM binds and pushes
  the first now-playing payload, `NotificationManagerCompat.notify(N,
  buildRich(...))` atomically replaces the default with the rich
  notification (book title, chapter, play/pause + stop actions). The
  notification ID is identical so the system replaces in place; the
  user sees one notification, not two.

- **POST_NOTIFICATIONS denied → graceful fallback.** On API 33+ if
  the user denies POST_NOTIFICATIONS, `EpubReaderViewModel.startTts`
  sets `_notificationPermissionDenied = true` (UI surface for an
  accessibility-grade explanatory Snackbar) and proceeds with V1
  foreground-only TTS — no service bind, no crash. Re-granting the
  permission while TTS is active triggers a late bind.

- **All PendingIntents are FLAG_IMMUTABLE and live inside the service
  module.** API 31+ requires the mutability flag; without IMMUTABLE
  a third-party app can hijack an intent. `GroupHSecurityTest.
  platform_pendingIntent_alwaysImmutable_andOnlyInServiceModule`
  pins both invariants by source-grep: (a) every PendingIntent
  factory call co-locates `FLAG_IMMUTABLE`; (b) construction appears
  only in `com/straysouth/lectern/service/`.

- **AudioSessionCoordinator multi-cap promotion is DEFERRED.** V2.9-A
  is TTS-only. When a second audio surface ships (STT in V2.7, ambient
  loop), the coordinator becomes a state machine per ADR-AND-A's
  "promote when a second audio surface is added" clause. V2.9 touches
  nothing in `AudioSessionCoordinator.kt`; the CI gate
  `check_audio_session.sh` continues to pass.

## Threat model — notification content

Three categories of data could appear in the notification surface. The
notification shade is screenshot-capturable independently from the app
(ADR-AND-R reconsideration trigger 5), so each category needs an
explicit decision.

| Data | In notification? | Rationale |
|---|---|---|
| Book title (`Book.title`) | **YES** | Already visible on the Library screen and in the system app-switcher; the notification adds no new exposure surface. |
| Chapter title (TOC entry / `Locator.title`) | **YES** | Same exposure class as book title — visible on the in-reader chapter rotor and TOC dialog. |
| TTS body text (the sentence currently being spoken) | **NEVER** | EPUB body text is what the user actively reads — surfacing it in the notification shade is a meaningful new exposure (shoulder-surf, screenshot, voice-assistant readout of the shade). Out of scope for V2.9-A and any successor. |
| Annotation body / note text | **NEVER** | User-authored private content (ADR-AND-T). The reader is FLAG_SECURE while annotations are on screen specifically to prevent screenshot leak; the notification would defeat that control. |
| Gaze indicator / focus-band position | **NEVER** | Behavioural-biometric-adjacent (ADR-AND-J / ADR-AND-L). No part of the gaze pipeline reaches the service. |
| Reading-progress percentage | **NO (V2.9-A)** | Marginal value; revisit if user research shows demand. |

The rich notification text equals what the Library screen and
in-reader chapter rotor already expose — exposure is proportionate,
not expanded, **on the unlocked notification shade**.

### FGS notification visibility (amendment 2026-05-25 — V2.9-A adversarial fix #1)

The proportional-exposure argument above does not extend to the
**lockscreen**. The lockscreen has a wider attacker model: anyone with
physical access can read it without authenticating, where the library
and in-reader rotor require an unlocked device. A `VISIBILITY_PUBLIC`
notification on the lockscreen leaks the user's current book title and
chapter to that broader attacker set; this is a real net-new exposure
even though the same data on the unlocked shade is proportionate.

Policy:

- Both `buildDefault` and `buildRich` set `VISIBILITY_PRIVATE`.
- `buildRich` calls `setPublicVersion(buildPublicRedacted(context))`
  with a redacted notification that shows only the app name and the
  generic "Reading" label — no book title, no chapter, no actions
  beyond what the OS already renders.
- `buildDefault` is already redacted-equivalent (app name + "Reading")
  so it serves as its own public version on the lockscreen.

Pinned by `GroupGSecurityTest.platform_serviceNotification_visibilityPrivate_andPublicRedacted`.

### Lazy permission policy (amendment 2026-05-25 — V2.9-A adversarial fixes #2 + #4)

`POST_NOTIFICATIONS` is **not** requested from `EpubReaderFragment.onCreate`.
Users who never open the TTS panel never see the runtime dialog. The
prompt is triggered lazily on the first `startTts()` attempt that
requires it (API 33+, permission not granted), via the VM's
`permissionRequestEvents` channel which the Fragment forwards to the
`ActivityResultLauncher` it owns.

The denied-Snackbar consumes one-shot **events** (`Channel<Unit>` →
`Flow<Unit>`), not a persistent `StateFlow<Boolean>`. The prior
`StateFlow<Boolean>` only fired on the false→true transition: a user
who denied once and re-attempted TTS saw nothing. Channel-backed events
fire on every denied attempt. The two fixes collapse: a user who
hasn't been prompted is prompted, denies → Snackbar; re-attempts →
Snackbar again. Every time.

Pinned by `GroupGSecurityTest.audhd_postNotifications_prompt_lazyAndOneShot`.

### FGS start exception policy (amendment 2026-05-25 — V2.9-A adversarial fix #3)

`ContextCompat.startForegroundService` may throw
`ForegroundServiceStartNotAllowedException` on API 31+ when the OS
determines the app is not allowed to start a foreground service at
that moment (no qualifying allowlist condition: not in foreground,
no recent user interaction, no exempt source such as
`FOREGROUND_SERVICE_MEDIA_PLAYBACK` priority granted by the system).

Policy:

- The call is wrapped in `try { ... } catch (e: IllegalStateException)`.
  `ForegroundServiceStartNotAllowedException` extends `IllegalStateException`
  and is the documented exception on this call path; catching the
  parent type keeps the API floor clean (minSdk 26, exception class
  added in API 31).
- On catch: fall back to **V1 foreground-only TTS** — `_ttsNavigator`
  is already non-null and `nav.play()` runs immediately after, so
  the user gets audio while the reader is foregrounded. The service
  is NOT bound and the rich notification does NOT appear.
- The VM emits `backgroundPlaybackUnavailableEvents` (distinct from
  `permissionDeniedEvents` so the Snackbar copy can name the failure
  mode: "Background playback unavailable. Read-aloud will continue
  while the reader is open.").

Pinned by `GroupGSecurityTest.platform_startForegroundService_exceptionGuarded`.

## Consequences

**Positive**

- ADR-AND-A invariant preserved: zero `AudioManager` calls outside
  `AudioSessionCoordinator.kt`. `check_audio_session.sh` continues to
  pass without modification.
- ADR-AND-R amendment is bounded: trigger 5 fires, but the FLAG_SECURE
  decision for the in-app reader surface is unchanged (FLAG_SECURE
  stays claimed via `SecureWindow()` while annotations are on screen
  per ADR-AND-T; the notification surface is a separate threat surface
  handled by the content policy above).
- Single cleanup path eliminates the "which side ran tear-down?"
  race that two-owner architectures create.

**Negative**

- The service must be bound for the rich notification to populate;
  during the brief default-then-rich window the notification shows
  generic "Reading" text. Acceptable — better than ANR.
- A `POST_NOTIFICATIONS`-denied user gets V1 foreground-only TTS
  silently from the platform's perspective (the in-app Snackbar via
  `notificationPermissionDenied` is the user-visible affordance).
  This is an explicit choice over a crash or a permission-request
  loop.
- V2.9-A does not lift the existing `EpubReaderFragment.onStop()`
  pause behaviour pinned by `GroupEFSecurityTest.tts_onStop_callsPauseTts`.
  TTS still pauses on background; the notification's Play action is
  the resume affordance. True uninterrupted background playback
  requires a separate Convention 3 test-gate replacement and is
  deferred.

## Alternatives considered

**Full-ownership service.** The service holds `_ttsNavigator` and
calls `AudioSessionCoordinator` directly. **Rejected** — would
require the VM to bind synchronously before TTS can start (binding
is async on Android), would create two callers into
`AudioSessionCoordinator`, and would split the cleanup path across
process-lifetime (service) and Fragment-lifetime (VM) owners.

**MediaSessionCompat-based architecture.** Use a `MediaSessionCompat`
+ `MediaBrowserServiceCompat` so the notification, lock-screen
controls, Bluetooth controls, and Android Auto integration come for
free. **Deferred to a future V2.9-B.** V2.9-A is sentinel-only;
adding `androidx.media`/`media3` requires a new dependency, which
needs an explicit approval per the surgical-engineer dependency
rule. Notification + foreground state alone delivers the
keep-alive-while-backgrounded behaviour V2.9 promises.

**No service; rely on the OS background-execution allowance.**
**Rejected** — the OS reclaims the process within seconds of the
Activity stopping; without a foreground service TTS will be killed
mid-utterance.

## Pinned by

| Guarantee | Test |
|---|---|
| Every PendingIntent uses `FLAG_IMMUTABLE`; construction is confined to `service/` | `GroupHSecurityTest.platform_pendingIntent_alwaysImmutable_andOnlyInServiceModule` |
| Service `onCreate` calls `startForeground` with the default builder result; `updateNowPlaying` is the rich-update path | `GroupGSecurityTest.audhd_serviceNotification_richContent_atomicUpdate` |
| VM start path gates the service bind on a `POST_NOTIFICATIONS` runtime check | `GroupGSecurityTest.platform_serviceStart_requiresPostNotificationsPermission` |
| Recents swipe → `onTaskRemoved` → bound callback → VM stop (single cleanup path) | `GroupGSecurityTest.platform_serviceCleanup_singlePathThroughViewModel` |
| Notifications use `VISIBILITY_PRIVATE`; rich notification ships a redacted public version for the lockscreen | `GroupGSecurityTest.platform_serviceNotification_visibilityPrivate_andPublicRedacted` |
| `startForegroundService` is wrapped in try/catch for `ForegroundServiceStartNotAllowedException` (API 31+) with V1 fallback + Snackbar | `GroupGSecurityTest.platform_startForegroundService_exceptionGuarded` |
| `POST_NOTIFICATIONS` prompt is lazy (first `startTts`, not `onCreate`); denied-Snackbar is one-shot per attempt | `GroupGSecurityTest.audhd_postNotifications_prompt_lazyAndOneShot` |
| Zero `AudioManager` calls outside `AudioSessionCoordinator.kt` | `scripts/check_audio_session.sh` (unchanged) |

## Cross-references

- `ADR-AND-A` — AudioSessionCoordinator sole-owner invariant.
- `ADR-AND-R` — FLAG_SECURE reconsideration triggers; trigger 5
  (foreground-service notification) fires here. See the
  `2026-05-25 amendment` in ADR-AND-R for the cross-link.
- `ADR-AND-T` — V2.2 annotations FLAG_SECURE decision; the
  notification's never-include-annotation-text rule preserves that
  control.
- `docs/plans/v2-scope.md §V2.9` — feature scope and open questions
  resolved by this ADR (TTS-only scope, rich notification, locked
  decisions).
