package com.straysouth.lectern.data.repository

data class FocusBandPrefs(
    // Default ON — sentence-level TTS highlight is the core AuDHD reading aid.
    val enabled: Boolean = true,
    // Default OFF per ADR-AND-D (new gaze visuals default to off). Controls the
    // V1 pixel-Y overlay in GazeFocusBandOverlay (ADR-AND-L Sprint 13 amendment).
    val gazeOverlayEnabled: Boolean = false,
)
