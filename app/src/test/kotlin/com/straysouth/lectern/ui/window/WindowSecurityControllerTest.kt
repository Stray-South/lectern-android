package com.straysouth.lectern.ui.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 infrastructure — reference-counted FLAG_SECURE controller.
 *
 * Pure-JVM tests against the lambda constructor; the real Window-bound
 * convenience constructor is exercised only at runtime on a device.
 *
 * The race-condition risk that motivated the reference-counter design is
 * documented in `docs/plans/v2-scope.md §Cross-cutting risk register`:
 * naive per-Composable DisposableEffect can clear FLAG_SECURE while a
 * sensitive screen is still visible if the outgoing screen's onDispose
 * runs after the incoming screen's composition. These tests pin the
 * invariant that the flag stays asserted as long as any active claim
 * exists, regardless of acquire/release interleaving.
 */
class WindowSecurityControllerTest {

    private val applyCalls = mutableListOf<Boolean>()
    private val controller = WindowSecurityController { enabled -> applyCalls.add(enabled) }

    @Test
    fun acquire_setsFlagOnFirstClaim() {
        controller.acquire()
        assertEquals(1, controller.activeClaimCount())
        assertEquals(listOf(true), applyCalls)
    }

    @Test
    fun release_clearsFlagOnLastRelease() {
        controller.acquire()
        controller.release()
        assertEquals(0, controller.activeClaimCount())
        assertEquals(listOf(true, false), applyCalls)
    }

    @Test
    fun secondAcquire_doesNotReSetFlag() {
        controller.acquire()
        controller.acquire()
        assertEquals(2, controller.activeClaimCount())
        // Flag is set exactly once — second acquire only increments the counter.
        assertEquals(listOf(true), applyCalls)
    }

    @Test
    fun intermediateRelease_doesNotClearFlag() {
        controller.acquire()
        controller.acquire()
        controller.release()
        // Counter dropped to 1; flag must STAY set because a sensitive screen
        // is still active. This is the navigation race-condition guard.
        assertEquals(1, controller.activeClaimCount())
        assertEquals(listOf(true), applyCalls)
    }

    @Test
    fun fullReleaseChain_clearsFlagExactlyOnce() {
        controller.acquire()
        controller.acquire()
        controller.acquire()
        controller.release()
        controller.release()
        controller.release()
        assertEquals(0, controller.activeClaimCount())
        // Set once on the first acquire, cleared once on the last release.
        assertEquals(listOf(true, false), applyCalls)
    }

    @Test
    fun navigationRaceScenario_outgoingDisposeAfterIncomingCompose() {
        // Reproduces the scenario from v2-scope.md risk register:
        // Screen A is sensitive (acquired). User navigates to Screen B (also sensitive).
        // Compose order: B.acquire() runs first (incoming composition), then A.release()
        // runs (outgoing disposal). With a naive DisposableEffect, B's flag would be set
        // then immediately cleared by A's onDispose. With reference counting, the counter
        // stays >= 1 throughout.
        controller.acquire()                // Screen A enters composition
        assertTrue(applyCalls.last())       // flag set
        controller.acquire()                // Screen B enters composition (incoming)
        controller.release()                // Screen A leaves composition (outgoing, delayed)
        // After both transitions, exactly one screen (B) is still active. Flag must stay set.
        assertEquals(1, controller.activeClaimCount())
        assertEquals(listOf(true), applyCalls)
    }

    @Test
    fun releaseWithoutAcquire_recoversToZero() {
        controller.release()
        // Counter should never go negative; controller corrects to 0 and clears the flag.
        assertEquals(0, controller.activeClaimCount())
        // The defensive recovery path STILL calls applyFlagSecure(false) once to ensure
        // the window flag is in the cleared state regardless of prior runtime drift.
        assertEquals(listOf(false), applyCalls)
    }

    @Test
    fun manyAlternatingClaims_endsCleared() {
        // Stress: 100 acquire/release pairs interleaved randomly. Flag should be set
        // when any claim is active and cleared when none are.
        var depth = 0
        val rng = java.util.Random(42)
        repeat(100) {
            if (depth == 0 || rng.nextBoolean()) {
                controller.acquire()
                depth++
            } else {
                controller.release()
                depth--
            }
        }
        while (depth > 0) {
            controller.release()
            depth--
        }
        assertEquals(0, controller.activeClaimCount())
        // Final state: flag is cleared. (The exact apply-call sequence varies with the
        // RNG seed; what matters is that the last apply call is `false`.)
        assertFalse("Flag must be cleared after all releases", applyCalls.last())
    }
}
