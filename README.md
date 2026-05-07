# Lectern Android

An AuDHD-first reading app for Android. Native Kotlin 2.3.21 / Jetpack Compose.

**Status:** In development — Sprint 1 scaffold complete.

**iOS version:** [Stray-South/lectern-ios](https://github.com/Stray-South/lectern-ios)

## Stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.3.21 |
| UI | Jetpack Compose + Material 3 |
| EPUB | Readium Kotlin 3.1.2 (Sprint 2) |
| Persistence | Room 2.8.4 + DataStore 1.2.1 |
| Gaze | MediaPipe Face Landmarker 0.10.32 (Sprint 6) |
| Min SDK | API 26 (Android 8.0) |

## Architecture

Two-repo structure with shared submodule:
- `lectern-ios` — Swift 6 / SwiftUI / TextKit 2
- `lectern-android` — Kotlin / Compose (this repo)
- `lectern-shared` — Design tokens, copy, CI scripts (planned Sprint 3)

See `RULES.md` for engineering constraints.
See `DEVLOG.md` for build log.

## Development

```bash
./gradlew assembleDebug      # build
./gradlew testDebugUnitTest  # unit tests
./gradlew detekt             # lint
./scripts/preflight.sh       # all gates
```
