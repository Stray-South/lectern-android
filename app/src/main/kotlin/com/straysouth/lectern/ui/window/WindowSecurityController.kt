package com.straysouth.lectern.ui.window

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * V2 infrastructure — reference-counted FLAG_SECURE controller.
 *
 * Lectern is a single-Activity Compose app. FLAG_SECURE is a Window-level
 * flag: once set, it applies to the entire Activity until cleared. Two naive
 * approaches both have correctness bugs documented in
 * `docs/plans/v2-scope.md §Cross-cutting risk register`:
 *
 *   - One-way `enableFlagSecureOnActivity()` — flag leaks onto Settings,
 *     Library, and any other non-sensitive screen the user navigates to.
 *
 *   - Per-Composable `DisposableEffect` that sets on enter and clears on
 *     dispose — under navigation between two sensitive screens, `onDispose`
 *     of the outgoing screen can run after composition of the incoming
 *     screen, clearing the flag while a sensitive Composable is still
 *     visible.
 *
 * This controller maintains a [counter] of currently-active "wants FLAG_SECURE"
 * claims. Each sensitive Composable wraps itself in [SecureWindow] (or calls
 * [acquire] / [release] manually for non-Composable surfaces); the flag is
 * set whenever `counter > 0` and cleared at 0. Increment/decrement ordering
 * during navigation no longer matters because the counter stays >= 1 as long
 * as any sensitive screen is active.
 *
 * Pinned by `ADR-AND-R` (V1: FLAG_SECURE deliberately absent; V2 amendments
 * land here when the first FLAG_SECURE-triggering feature ships).
 *
 * Threading: [acquire] and [release] are thread-safe via [AtomicInteger];
 * actual flag manipulation is dispatched to the Window's main looper.
 *
 * Not a singleton — bound at the Activity scope and exposed via
 * [LocalWindowSecurityController]. Tests construct a controller with a
 * synthetic Window or a no-op fake.
 */
class WindowSecurityController(private val applyFlagSecure: (Boolean) -> Unit) {

    /**
     * Convenience constructor that binds to a real [Window].
     *
     * The setter is posted to the Window's decor-view looper so background-
     * thread `acquire()` / `release()` calls don't crash with
     * `CalledFromWrongThreadException`. Production code uses this constructor;
     * tests use the primary constructor with a synchronous fake setter.
     */
    constructor(window: Window) : this({ enabled ->
        window.decorView.post {
            if (enabled) {
                window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    })

    // Lock object guarding the counter + flag-setter as a single atomic transition.
    // AtomicInteger alone is insufficient because the `if (newCount == 1)` check and
    // the subsequent applyFlagSecure() call are TWO operations from the JMM's view;
    // a concurrent release that drives the counter to 0 between them could clear the
    // flag while the acquire still expects it set, or vice versa. Synchronising the
    // whole transition guarantees the (counter, flag) pair is consistent.
    private val lock = Any()
    private var counter = 0

    /**
     * Add one "wants FLAG_SECURE" claim. Sets the flag if this was the first
     * active claim. Counter increment and flag setter run inside [lock] so the
     * (counter, flag) transition is atomic w.r.t. concurrent acquire/release.
     */
    fun acquire() {
        synchronized(lock) {
            counter++
            if (counter == 1) {
                applyFlagSecure(true)
            }
        }
    }

    /**
     * Drop one "wants FLAG_SECURE" claim. Clears the flag if this was the
     * last active claim. Defensive: a release without a paired acquire
     * (count would go negative) is silently corrected back to 0.
     *
     * The recovery is silent rather than logged because the controller is
     * also invoked from JVM-only tests where `android.util.Log` is unmocked
     * and would throw. The underlying acquire/release imbalance is caught
     * at code-review time by the [SecureWindow] DisposableEffect pairing.
     */
    fun release() {
        synchronized(lock) {
            counter--
            when {
                counter == 0 -> applyFlagSecure(false)
                counter < 0 -> {
                    // Recover: counter should never go below 0. Force back to 0 and
                    // clear the flag.
                    counter = 0
                    applyFlagSecure(false)
                }
            }
        }
    }

    /**
     * Test/diagnostic hook. Returns the current active-claim count.
     */
    fun activeClaimCount(): Int = synchronized(lock) { counter }

}

/**
 * CompositionLocal carrying the Activity-scoped [WindowSecurityController].
 *
 * Default value is `null`. The Activity provides a real controller at the
 * root via [CompositionLocalProvider]. Composables that don't need FLAG_SECURE
 * can ignore this local; composables that DO need it call [SecureWindow] or
 * read the local directly.
 *
 * A `null` value means "no controller installed" — typically only happens
 * in previews or tests. [SecureWindow] tolerates a null local (no-op).
 */
val LocalWindowSecurityController = compositionLocalOf<WindowSecurityController?> { null }

/**
 * Composable side-effect that wants FLAG_SECURE while it's in the composition.
 *
 * Usage:
 * ```
 * @Composable
 * fun AnnotationReader() {
 *     SecureWindow()  // claim while this composable is in tree
 *     // ... reader content ...
 * }
 * ```
 *
 * If multiple sensitive composables are simultaneously visible (e.g.
 * annotation reader showing a notes pane that also requires FLAG_SECURE),
 * each calls [SecureWindow] independently and the reference counter keeps
 * the flag set until all sensitive surfaces have left composition.
 *
 * No-op if [LocalWindowSecurityController] is null (preview / test path).
 */
@Composable
fun SecureWindow() {
    val controller = LocalWindowSecurityController.current ?: return
    DisposableEffect(controller) {
        controller.acquire()
        onDispose { controller.release() }
    }
}
