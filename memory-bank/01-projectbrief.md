# 01 — Project Brief

## Mission

Lectern Android is an AuDHD-first reading app for readers with attention
and processing differences. Evidence-based typography, TTS with
sentence-level decoration, gaze-assisted focus features, and zero
analytics / streaks / loss-framing.

## Scope (V1)

- Local library (EPUB, PDF, CBZ / CBR)
- EPUB reader via Readium 3.1.2 + TTS via Readium `TtsNavigator`
- PDF reader via Android `PdfRenderer`
- Comics reader via bitmap pipeline (zip4j `getInputStream` only — no
  on-disk extraction)
- Gaze tracking (CameraX + MediaPipe FaceLandmarker, calibration + overlay)
- Typography panel (font / size / line-height / theme)
- Reading position persistence + anchor markers

## Out of scope (V1)

- Cloud sync (V2 — Supabase planned)
- Annotations (V2)
- Retrieval / spaced repetition (V2+)
- RSVP reader (V2+)
- Login / authentication
- DRM (no LCP adapter in `gradle/libs.versions.toml`)
- Notifications, widgets, alarms (zero `PendingIntent` in main sources)

## Sibling repo

`lectern-ios` (Swift 6 / SwiftUI / TextKit 2) — separate codebase, shared
design philosophy. Cross-platform parity tracked via ADRs
(`docs/adr/ADR-AND-*` mirror iOS ADRs).
