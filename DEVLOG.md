# Lectern Android — Developer Log

Append-only. Newest entries at the bottom.
Format: see .claude/skills/devlog/SKILL.md

<!-- LAST_DIGEST: 2026-05-07 00:00 UTC -->

## 2026-05-07T00:00Z — Sprint 1 scaffold
- **Did:** Created lectern-android repo with Gradle KTS, Compose BOM
  2026.04.01, Room 2.8.4, DataStore 1.2.1, Kotlin 2.3.21, AGP 9.1.1.
  Blank Compose activity launches on API 26 emulator.
- **Why:** Establishing verified build baseline before any feature code.
- **Files:** settings.gradle.kts, gradle/libs.versions.toml,
  app/build.gradle.kts, MainActivity.kt, Theme.kt, AndroidManifest.xml,
  RULES.md, .github/workflows/ci.yml, scripts/check_banned_strings.sh,
  scripts/check_gaze_data_leak.sh, DEVLOG.md
- **Next:** Sprint 2 — Readium 3.1.2 EPUB ingestion + basic reader screen.
- **Blockers:** none

## 2026-05-07T00:00Z — Sprint 2 EPUB reader
- **Did:** Full Library→Reader flow with Readium 3.1.2. Compose state-based
  navigation (rememberSaveable + BackHandler). AndroidFragment<EpubReaderFragment>
  hosts EpubNavigatorFragment via childFragmentManager. Import affordance:
  FloatingActionButton + ACTION_OPEN_DOCUMENT + takePersistableUriPermission +
  Readium title extraction + BookDao.upsert (deterministic UUID). Loading/error
  overlay (ComposeView) over WebView. WCAG AA/AAA palette, 48dp touch targets,
  isSystemInDarkTheme(). All hardcoded strings moved to strings.xml.
  check_banned_strings.sh extended to .kt files. configChanges added to manifest.
  KSP corrected to 2.3.21-1.0.32.
- **Why:** Sprint 1 delivered a buildable shell; Sprint 2 delivers a usable
  app — import an EPUB, open it, navigate back.
- **Files:** MainActivity.kt, EpubReaderFragment.kt, EpubReaderViewModel.kt,
  ReaderScreen.kt, ReaderOverlay.kt (new), LibraryScreen.kt, LibraryViewModel.kt,
  Theme.kt, LocatorRepository.kt, strings.xml, AndroidManifest.xml,
  libs.versions.toml, build.gradle.kts, check_banned_strings.sh
- **Next:** Sprint 3 — Typography panel (font, size, line height, background).
- **Blockers:** none
