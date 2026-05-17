# 02 — Product Context

## Who this serves

Readers with AuDHD, dyslexia, and other attention / processing
differences. The privacy posture is the product, not a feature — users
with these differences need to trust that camera, microphone, and
reading-pattern data is not exfiltrated.

## Hard editorial exclusions (RULES.md §AuDHD copy)

Forbidden tokens — never appear in `strings.xml`, never appear as
user-facing literals in `.kt` source:

- `streak`, `consecutive`, `wrong`, `incorrect`, `failed`, `missed`
- `great job`, `keep it up`, `daily goal`
- 🔥, 🏆, ⭐

Enforced by `scripts/check_banned_strings.sh` (XML + .kt two-pass scan).
Log.* and Exception(...) lines exempted (diagnostic, not user-facing).

## Hard product exclusions

- No streak / chain / contingent-reward mechanics anywhere
- No urgency / loss-framing copy
- No "don't lose your progress" language
- No leaderboard or social comparison surfaces
- No therapeutic claims in any user-visible string
- No analytics / telemetry SDK (see `scripts/check_banned_deps.sh`)

## Why these exclusions

Streak mechanics and contingent rewards measurably reduce intrinsic
motivation (Deci, Koestner & Ryan 1999, RCT/META, d = -0.40). For AuDHD
readers specifically, broken streaks and "you missed a day" notifications
create shame loops that suppress reading behavior — the opposite of the
product goal.

## Privacy positions

- All user data persists locally (Room + DataStore). No cloud in V1.
- Gaze biometric data (iris UV, raw frames) is in-memory only —
  only the 13-double `CalibrationResult` weight vector ever reaches
  disk, and that goes to app-private DataStore excluded from cloud
  backup and D2D transfer.
- `allowBackup = false` in `AndroidManifest.xml`.
- Camera permission gated at runtime (front camera not required at
  install).
- No microphone permission requested (no STT in V1).
