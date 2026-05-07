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
