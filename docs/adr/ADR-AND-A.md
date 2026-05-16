# ADR-AND-A: AudioSessionCoordinator is the sole owner of AudioManager focus state

**Status:** Accepted
**Date:** 2026-05-16
**Sprint:** 24

## Context

iOS ADR-A specifies that one actor — `AudioSessionCoordinator` — owns
`AVAudioSession` state for the entire app. Direct calls to
`setCategory` / `setMode` / `setActive` from any other file are
forbidden and enforced by `scripts/check_audio_session.sh`.

Android V1 originally placed audio-focus management inside
`EpubReaderViewModel.kt`. Sprint 20 hardened that path through three
review rounds (GAIN_TRANSIENT not MAY_DUCK; granted-check before
`nav.play()`; resume-path re-request). The hardening is correct but
the location is wrong: a feature ViewModel that owns global audio-focus
state cannot coordinate when a second audio surface — ambient loop, STT
— is added.

## Decision

`com.straysouth.lectern.audio.AudioSessionCoordinator` is the sole file
in `app/src/main/kotlin/` permitted to call
`AudioManager.requestAudioFocus`, `AudioManager.abandonAudioFocusRequest`,
or to construct `AudioFocusRequest.Builder`. All other files must route
audio-focus transitions through the coordinator.

The coordinator exposes three methods sufficient for V1's one capability
(TTS):

| Method | Purpose |
|---|---|
| `acquireForTts(onLoss: () -> Unit): Boolean` | Requests TRANSIENT focus for TTS playback. Returns true on AUDIOFOCUS_REQUEST_GRANTED. Stores the request for later re-acquire. `onLoss` runs on the audio-focus thread when transient or full loss occurs. |
| `reacquire(): Boolean` | Re-requests focus using the previously-stored builder config (preserves the original `onLoss` listener). Returns true if granted. Used for resume-after-transient-loss. |
| `release()` | Abandons any held request. Idempotent. |

Sprint 20 invariants are preserved at the coordinator boundary:
- `AUDIOFOCUS_GAIN_TRANSIENT` (not `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`)
- `requestAudioFocus` return value checked; only `AUDIOFOCUS_REQUEST_GRANTED`
  counts as held
- `AudioAttributes`: `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`

## Scope

V1 has one capability (`tts`). Promote to a multi-capability state
machine (matching iOS 25-cell matrix) when a second audio surface
(ambient loop, STT) ships. Do not pre-emptively design state
transitions for surfaces that do not exist.

## Pinned by

| Guarantee | Source |
|---|---|
| Coordinator contains the focus API calls + GAIN_TRANSIENT | `GroupEFSecurityTest.tts_audioFocus_ownedByAudioSessionCoordinator` |
| EpubReaderViewModel does not call focus APIs directly + references coordinator | `GroupEFSecurityTest.tts_viewModelDelegatesToAudioSessionCoordinator` |
| Repo-wide sole-owner enforcement | `scripts/check_audio_session.sh` (shipped in PR-C2) |

## Code markers

- `app/src/main/kotlin/com/straysouth/lectern/audio/AudioSessionCoordinator.kt`
- `app/src/main/kotlin/com/straysouth/lectern/ui/reader/EpubReaderViewModel.kt` (delegates)
- `RULES.md` §Audio
- `scripts/check_audio_session.sh` (CI grep gate — see ADR-AND-A
  §"Pinned by")

## Consequences

- Adding `AudioManager.requestAudioFocus` to any file other than the
  coordinator fails CI (via `check_audio_session.sh`) and fails the
  delegation test (via `tts_viewModelDelegatesToAudioSessionCoordinator`).
- Introducing a second audio surface requires promoting the coordinator
  to a state machine. The current 3-method API is intentional V1 scope.
- This ADR does **not** govern `AudioTrack` or `AudioRecord` — neither is
  in use today. If introduced (e.g. ambient loop via `AudioTrack`, or STT
  via `AudioRecord`), this ADR must be extended.
- This ADR does **not** govern `MediaPlayer` / `ExoPlayer` — none in use.
- The coordinator currently has no behavioural unit tests (mocking
  `AudioManager` is non-trivial); the source-substring tests above
  + the CI grep gate are the V1 enforcement. A follow-up PR may add
  behavioural tests with an injected `AudioManager` seam.
