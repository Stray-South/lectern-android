package com.straysouth.lectern.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Sole owner of [AudioManager] focus state for the app.
 *
 * See `docs/adr/ADR-AND-A.md` and `RULES.md` §Audio. Direct
 * [AudioManager.requestAudioFocus] / [AudioManager.abandonAudioFocusRequest]
 * calls outside this class are forbidden and enforced by
 * `scripts/check_audio_session.sh`.
 *
 * V1 supports one capability — TTS. Promote to a multi-capability state
 * machine when a second audio surface (ambient loop, STT) is added.
 *
 * Sprint 20 invariants preserved here:
 *  - AUDIOFOCUS_GAIN_TRANSIENT (not MAY_DUCK) — competing audio pauses,
 *    not ducks, because TTS is spoken word.
 *  - Return value of requestAudioFocus is checked; only AUDIOFOCUS_REQUEST_GRANTED
 *    counts as held.
 *  - The resume path re-uses the original builder config via [reacquire].
 */
class AudioSessionCoordinator(context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(AudioManager::class.java)

    private var request: AudioFocusRequest? = null

    /**
     * Requests TRANSIENT audio focus for TTS playback. Returns true on
     * AUDIOFOCUS_REQUEST_GRANTED; false otherwise (e.g. an active call
     * holds exclusive focus). When false, the caller must not start TTS.
     *
     * @param onLoss invoked on AUDIOFOCUS_LOSS / AUDIOFOCUS_LOSS_TRANSIENT;
     *               runs on the audio focus thread, callers must be safe
     *               to receive from arbitrary threads.
     */
    fun acquireForTts(onLoss: () -> Unit): Boolean {
        val req = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    onLoss()
                }
            }
            .build()
        val granted = audioManager.requestAudioFocus(req)
        return if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            request = req
            true
        } else {
            false
        }
    }

    /**
     * Re-requests focus using the existing builder config (and its
     * previously-registered onLoss listener), for resume after
     * AUDIOFOCUS_LOSS_TRANSIENT. Returns true if focus is granted; false
     * if no prior [acquireForTts] succeeded or the re-request was denied.
     */
    fun reacquire(): Boolean {
        val req = request ?: return false
        val granted = audioManager.requestAudioFocus(req)
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Abandons any held focus request. Idempotent. */
    fun release() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }
}
