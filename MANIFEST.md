# MANIFEST — Lectern Android

Single lookup table for authoritative documents and surfaces.
Mirrors `lectern-ios/MANIFEST.md` adapted for the Android codebase.

## Repo files

| Document | Path | Purpose |
|---|---|---|
| Engineering rules | `RULES.md` | CI-enforced quality gates |
| Build log | `DEVLOG.md` | Append-only sprint history (1100+ lines) |
| Project README | `README.md` | Stack summary + dev commands |
| Working-mode rules | `.claude/surgical-engineer.md` | 4-phase Research → Plan → Act → Verify workflow |
| RED-TEAM coverage | `docs/security/RED-TEAM.md` | Security & adversarial test surface map |
| V2 scope plan | `docs/plans/v2-scope.md` | Scoping for 9 deferred V2 features + ADR-AND-S+ slot reservations |

## Memory bank

| File | Path | Content |
|---|---|---|
| Project brief | `memory-bank/01-projectbrief.md` | Mission, scope, target users |
| Product context | `memory-bank/02-productContext.md` | Why it exists, AuDHD / dyslexia focus |
| System patterns | `memory-bank/03-systemPatterns.md` | Compose + Readium + Room patterns |
| Tech context | `memory-bank/04-techContext.md` | Versions, minSdk, dependencies |
| Active context | `memory-bank/05-activeContext.md` | Current sprint state, in-flight branches |
| Progress | `memory-bank/06-progress.md` | Shipped features + test coverage |

## ADR registry

Letters used: A through R (no S+ yet).

### Currently on disk in this branch / stack

- `docs/adr/ADR-AND-A.md` — AudioSessionCoordinator sole-owner
  (added on `refactor/audio-session-coordinator`, present on Set 4+5 stack)
- `docs/adr/ADR-AND-L.md` — Focus Band scope / WebView limitation
  (already on trunk)
- `docs/adr/ADR-AND-R.md` — FLAG_SECURE absence
  (added on `docs/adr-and-r-flag-secure`, present on Set 4+5 stack)

### Not on disk in this branch — live on parallel un-merged branches

- `docs/adr/ADR-AND-E.md` — Gaze stack: MediaPipe-on-RGB
  (branch: `docs/adr-and-e-gaze-stack`)
- `docs/adr/ADR-AND-{B,C,D,F,G,H,I,J,K,M,N,O,P,Q}.md` — Backfill of
  iOS-mirror ADRs (branch: `docs/adr-and-backfill`)

After Track A merges, all `ADR-AND-{A..R}` entries become trunk-reachable.

## CI scripts

| Script | Purpose | Enforces |
|---|---|---|
| `scripts/preflight.sh` | Local 9-gate runner | All below |
| `scripts/check_banned_strings.sh` | AuDHD copy lint (strings.xml + .kt) | RULES.md §AuDHD copy |
| `scripts/check_gaze_data_leak.sh` | No `@Entity` / DataStore key matching face\|eye\|gaze\|lookAt | RULES.md §Privacy |
| `scripts/check_audio_session.sh` | AudioSessionCoordinator sole-owner | ADR-AND-A |
| `scripts/check_banned_deps.sh` | Analytics / telemetry vendor ban | RULES.md §Privacy |
| `scripts/check_release_logging.sh` | `Log.d` / `Log.v` / `println` ban in main | RULES.md §Code quality |
| `scripts/download_models.sh` | Pulls MediaPipe `face_landmarker.task` | (asset bootstrap, not a CI gate) |

## Security test groups

All under `app/src/test/kotlin/com/straysouth/lectern/security/`.

| Group | File | Tests | Surface |
|---|---|---|---|
| A | `GroupASecurityTest.kt` | 14 | EPUB WebView + JS-interface + locator serialization |
| B | `GroupBSecurityTest.kt` | 11 | DataStore + cloud-backup exclusions + book-cache id |
| C | `GroupCSecurityTest.kt` | 7 | Room schema + migrations |
| D | `GroupDSecurityTest.kt` | 13 | Gaze module log hygiene + datastore exclusion paths |
| E/F | `GroupEFSecurityTest.kt` | 15 | TTS + audio focus + supply chain |
| G | `GroupGSecurityTest.kt` | 7 | AuDHD copy + gaze-overlay default-OFF + ridge degenerate |
| H | `GroupHSecurityTest.kt` | 11 | Platform / Manifest / exported / FLAG_SECURE / PendingIntent |
| I/J | `GroupIJSecurityTest.kt` | 12 | Coroutines + confined dispatcher + gaze ephemerality |

Total: 90 security tests + 1 example = 91 JVM unit tests.
Instrumented tests: 6 (under `app/src/androidTest/`).

## External references

| System | Path | Notes |
|---|---|---|
| iOS sibling | `lectern-ios` repo (not local submodule) | Cross-platform parity tracked via ADRs |
| Memory bank concept | iOS `memory-bank/` directory | Per-session synopsis pointing to DEVLOG for detail |
