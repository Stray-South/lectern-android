package com.straysouth.lectern.ui.reader

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.straysouth.lectern.R
import com.straysouth.lectern.ui.gaze.CALIBRATION_TOTAL_POINTS
import com.straysouth.lectern.ui.gaze.GazeViewModel
import com.straysouth.lectern.ui.theme.LecternTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import android.webkit.WebView
import androidx.webkit.WebViewClientCompat
import org.readium.r2.navigator.epub.EpubNavigatorFragment

class EpubReaderFragment : Fragment() {

    private val viewModel: EpubReaderViewModel by viewModels()
    private val gazeViewModel: GazeViewModel by activityViewModels()

    private lateinit var overlay: ComposeView

    // Assigned once the navigator fragment is committed; used to submit live pref updates
    // and apply TTS word decorations.
    private var navigatorFragment: EpubNavigatorFragment? = null

    // Guards the childFragmentManager.commit so it runs exactly once per Fragment instance.
    // repeatOnLifecycle(STARTED) relaunches its block on every STOPPED→STARTED transition;
    // without this flag the navigator would be replaced (and position reset) on every foreground.
    private var navigatorCommitted = false

    // SECURITY A.2: Guards FragmentLifecycleCallbacks registration so it runs exactly once.
    // Same pattern as navigatorCommitted — repeatOnLifecycle re-enters on every STARTED cycle
    // and FragmentManager does not deduplicate registrations. Re-registering would accumulate
    // redundant callbacks (each firing on every page-fragment view creation) and leak the
    // anonymous object closures held by the child FragmentManager across lifecycle transitions.
    private var blockingCallbackRegistered = false

    companion object {
        // Public so ReaderScreen can build the arguments Bundle via AndroidFragment.
        // Direct instantiation is not supported — use AndroidFragment<EpubReaderFragment>
        // with arguments = bundleOf(ARG_BOOK_ID to bookId).
        const val ARG_BOOK_ID = "book_id"
        private const val TAG_NAVIGATOR = "epub_navigator"
        private const val TTS_DECORATION_GROUP = "tts"
        private const val FOCUS_BAND_DECORATION_GROUP = "focus_band"
        private const val ANCHOR_DECORATION_GROUP = "visual_anchor"
        private val CONTAINER_ID get() = R.id.epub_reader_container
        // Amber at 40% alpha — word-level TTS highlight.
        private val TTS_HIGHLIGHT_TINT = Color.argb(102, 255, 215, 0)
        // Warm yellow at 30% alpha — sentence-level Focus Band.
        private val FOCUS_BAND_TINT = Color.argb(77, 255, 235, 59)
        // Warm yellow at 50% alpha — pinned Visual Anchor (more prominent than live band).
        private val ANCHOR_TINT = Color.argb(128, 255, 235, 59)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val bookId = arguments?.getString(ARG_BOOK_ID)
        // Dummy factory must be set before super.onCreate() so FragmentManager can
        // safely restore EpubNavigatorFragment on config change or process death.
        childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
        if (bookId == null) return
        // True only on config change: ViewModel survived, existing navigator is functional.
        val isConfigChange = viewModel.state.value is EpubReaderViewModel.State.Ready
        viewModel.load(bookId)
        setupNavigator(isConfigChange, bookId)
        setupTypographyObserver()
        setupTtsObserver()
        setupAnchorObserver()
        setupGazeTtsBridge()
    }

    // E.1 fix: pause TTS when the Fragment stops (app backgrounded, call screen, etc.)
    // so audio does not continue after the user leaves the app. Resume is not automatic;
    // the user taps Play again — consistent with expected media-playback UX.
    override fun onStop() {
        super.onStop()
        viewModel.pauseTts()
    }

    // ── Private lifecycle helpers ─────────────────────────────────────────────

    private fun setupNavigator(isConfigChange: Boolean, bookId: String) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state
                    .filterIsInstance<EpubReaderViewModel.State.Ready>()
                    .take(1)
                    .collect { state ->
                        // SECURITY A.3: listener intentionally null — no EpubNavigatorFragment.Listener
                        // is passed to createFragmentFactory(). This means onExternalLinkActivated()
                        // is never called for external/scheme URLs, so intent:// and market:// links
                        // from EPUB content cannot fire Android Intents. Any future listener MUST
                        // allowlist only https/http schemes before calling startActivity().
                        childFragmentManager.fragmentFactory =
                            state.navigatorFactory.createFragmentFactory(
                                initialLocator = state.initialLocator,
                                initialPreferences = state.initialTypography,
                            )
                        if (!isConfigChange && !navigatorCommitted) {
                            childFragmentManager.commit {
                                setReorderingAllowed(true)
                                replace(CONTAINER_ID, EpubNavigatorFragment::class.java, Bundle(), TAG_NAVIGATOR)
                            }
                            childFragmentManager.executePendingTransactions()
                            navigatorCommitted = true
                        }
                        navigatorFragment =
                            childFragmentManager.findFragmentByTag(TAG_NAVIGATOR)
                                as? EpubNavigatorFragment
                        val fragment = navigatorFragment ?: return@collect

                        // SECURITY A.2: Block external resource loads from EPUB content.
                        // R2EpubPageFragment instances are created lazily by ViewPager as the user
                        // navigates between chapters. We wrap each page's WebViewClient as its view
                        // is created so all pages — including those not yet instantiated — are covered.
                        // EpubBlockingWebViewClient blocks any host other than "readium" (Readium's
                        // local publication server) while delegating all other callbacks unchanged.
                        //
                        // SECURITY A.1: JS is enabled by Readium (required for decorations/MathML).
                        // window.Android exposes: onTap, onDrag*, onKey, onDecorationActivated,
                        // onSelectionStart/End, getViewportWidth, logError. The logError interface
                        // writes to Logcat but is harmless on release builds (debuggable=false).
                        // No file system, content://, or network capabilities are exposed.
                        if (!blockingCallbackRegistered) {
                            fragment.childFragmentManager.registerFragmentLifecycleCallbacks(
                                object : FragmentManager.FragmentLifecycleCallbacks() {
                                    override fun onFragmentViewCreated(
                                        fm: FragmentManager,
                                        f: Fragment,
                                        v: View,
                                        savedInstanceState: Bundle?,
                                    ) {
                                        // R2EpubPageFragment is internal to the Readium module —
                                        // we cannot reference it directly. Instead, walk the view
                                        // tree of every child fragment looking for WebViews.
                                        wrapWebViewsIn(v)
                                    }
                                },
                                // recursive=true: resilient to future Readium nesting changes in
                                // R2PagerAdapter. The !is EpubBlockingWebViewClient guard in
                                // wrapWebViewsIn prevents double-wrapping regardless of depth.
                                /* recursive = */ true,
                            )
                            blockingCallbackRegistered = true
                        }

                        launch {
                            // StateFlow already skips equal values; no distinctUntilChanged needed.
                            fragment.currentLocator
                                .collect { locator -> viewModel.saveLocator(bookId, locator) }
                        }
                    }
            }
        }
    }

    private fun setupTypographyObserver() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.typographyPrefs.collect { prefs ->
                    navigatorFragment?.submitPreferences(prefs.toEpubPreferences())
                }
            }
        }
    }

    private fun setupTtsObserver() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.ttsUiState, viewModel.focusBandPrefs) { state, focusBand ->
                    state to focusBand
                }.collect { (state, focusBand) ->
                    val active = state as? TtsUiState.Active

                    // Word-level amber highlight
                    val wordDecorations = active?.tokenLocator?.let { loc ->
                        listOf(Decoration("tts_word", loc, Decoration.Style.Highlight(tint = TTS_HIGHLIGHT_TINT)))
                    } ?: emptyList()
                    navigatorFragment?.applyDecorations(wordDecorations, TTS_DECORATION_GROUP)

                    // Sentence-level focus band — only when enabled
                    val bandDecorations = if (focusBand.enabled) {
                        active?.utteranceLocator?.let { loc ->
                            listOf(
                                Decoration(
                                    "focus_band_sentence",
                                    loc,
                                    Decoration.Style.Highlight(tint = FOCUS_BAND_TINT),
                                ),
                            )
                        } ?: emptyList()
                    } else {
                        emptyList()
                    }
                    navigatorFragment?.applyDecorations(bandDecorations, FOCUS_BAND_DECORATION_GROUP)
                }
            }
        }
    }

    /**
     * Pauses gaze inference while TTS is actively playing; resumes on pause/stop.
     * Uses pauseAnalysis/resumeAnalysis (clearAnalyzer/setAnalyzer) — ~0 ms, no
     * CameraX rebind, no GPU delegate teardown. Cuts CPU/thermal load on low-end
     * minSdk 26 hardware when both pipelines would otherwise run concurrently.
     */
    private fun setupGazeTtsBridge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ttsUiState.collect { state ->
                    if (state is TtsUiState.Active && state.isPlaying) {
                        gazeViewModel.pauseForTts()
                    } else {
                        gazeViewModel.resumeFromTts()
                    }
                }
            }
        }
    }

    private fun setupAnchorObserver() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.anchorLocator.collect { locator ->
                    val decorations = locator?.let {
                        listOf(Decoration("visual_anchor", it, Decoration.Style.Highlight(tint = ANCHOR_TINT)))
                    } ?: emptyList()
                    navigatorFragment?.applyDecorations(decorations, ANCHOR_DECORATION_GROUP)
                }
            }
        }
    }

    /**
     * Recursively walks [root] and wraps the client of every [WebView] found with
     * [EpubBlockingWebViewClient], unless already wrapped (idempotent on config change).
     *
     * We cannot reference R2EpubPageFragment (Readium internal class) directly, so view
     * traversal is the only public-API way to reach the WebView. The guard against
     * double-wrapping ensures repeatOnLifecycle re-entry and config changes are safe.
     */
    private fun wrapWebViewsIn(root: View) {
        if (root is WebView) {
            // webViewClient (API 26+) returns the current client; wrapping is idempotent.
            val existing = root.webViewClient
            when {
                existing is EpubBlockingWebViewClient -> {
                    // Already wrapped — idempotent on config change / re-entry.
                }
                existing is WebViewClientCompat -> {
                    // Readium sets a WebViewClientCompat subclass. Wrap it.
                    root.setWebViewClient(EpubBlockingWebViewClient(existing))
                }
                else -> {
                    // Unexpected: a future Readium version set a plain WebViewClient instead of
                    // WebViewClientCompat. We cannot safely wrap it (constructor requires Compat).
                    // Log and continue — allowContentAccess is still applied below.
                    android.util.Log.w(
                        "EpubReaderFragment",
                        "wrapWebViewsIn: unexpected WebViewClient type ${existing::class.java.name}; " +
                            "external-resource blocking NOT applied. Update EpubBlockingWebViewClient.",
                    )
                }
            }
            // SECURITY A.5: content:// access is true by default and Readium never disables it.
            // Readium's asset server uses https://readium/ exclusively — no content:// URIs are
            // needed. Explicitly disabling closes the surface for EPUB JS calling content://
            // URIs to access app data (contacts, media store, etc.).
            // Applied regardless of client-wrap outcome above.
            root.settings.allowContentAccess = false
        } else if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                wrapWebViewsIn(root.getChildAt(i))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = FrameLayout(requireContext())

        val navigatorContainer = FrameLayout(requireContext()).apply {
            id = CONTAINER_ID
        }
        root.addView(navigatorContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        overlay = ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LecternTheme {
                    val state by viewModel.state.collectAsState()
                    val typographyPrefs by viewModel.typographyPrefs.collectAsState()
                    val ttsUiState by viewModel.ttsUiState.collectAsState()
                    val ttsPrefs by viewModel.ttsPrefs.collectAsState()
                    val focusBandPrefs by viewModel.focusBandPrefs.collectAsState()
                    val anchorLocator by viewModel.anchorLocator.collectAsState()
                    val gazeState by gazeViewModel.gazeState.collectAsState()
                    val gazeEnabled by gazeViewModel.gazeEnabled.collectAsState()
                    ReaderOverlay(
                        state = state,
                        typographyPrefs = typographyPrefs,
                        ttsUiState = ttsUiState,
                        ttsPrefs = ttsPrefs,
                        focusBandPrefs = focusBandPrefs,
                        anchorActive = anchorLocator != null,
                        gazeState = gazeState,
                        gazeEnabled = gazeEnabled,
                        onBack = { activity?.onBackPressedDispatcher?.onBackPressed() },
                        onTypographyChange = viewModel::updateTypography,
                        // Pass current navigator position so TTS starts where the user is reading,
                        // not at the beginning of the book. Null if navigator not yet attached.
                        onTtsPlay = { viewModel.startTts(navigatorFragment?.currentLocator?.value) },
                        onTtsPause = viewModel::pauseTts,
                        onTtsStop = viewModel::stopTts,
                        onTtsSpeedChange = viewModel::updateTtsSpeed,
                        onFocusBandChange = viewModel::updateFocusBand,
                        onDismissTtsUnavailable = viewModel::dismissTtsUnavailable,
                        onAnchorDismiss = viewModel::clearAnchor,
                        // onGazeToggle is wired from MainActivity via ReaderScreen args;
                        // the Fragment accesses it through the ViewModel toggle path.
                        onGazeToggle = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                requireContext(), Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            gazeViewModel.toggleGaze(hasPermission = hasPermission)
                        },
                        onCalibrate = {
                            gazeViewModel.startCalibration(CALIBRATION_TOTAL_POINTS)
                        },
                    )
                    // TODO(ADR-AND-L): Focus Band V2 — deferred to V3.
                    // V1 pixel overlay is GazeFocusBandOverlay in ReaderOverlay.kt (Sprint 13).
                    // V2: when a native BasicText surface exists, replace with:
                    //   bandCenterY = gazePoint.y → getLineForOffset → drawRect dim above/below
                }
            }
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        return root
    }
}
