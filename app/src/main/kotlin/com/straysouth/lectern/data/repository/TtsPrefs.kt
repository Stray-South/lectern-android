package com.straysouth.lectern.data.repository

data class TtsPrefs(
    // Android TTS speed multiplier; UI caps at 2.0× (RULES.md RSVP cap ~350 wpm at 2×)
    val speed: Double = 1.0,
)
