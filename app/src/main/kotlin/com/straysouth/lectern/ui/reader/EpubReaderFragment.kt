package com.straysouth.lectern.ui.reader

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.straysouth.lectern.R
import com.straysouth.lectern.ui.theme.LecternTheme
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment

class EpubReaderFragment : Fragment() {

    private val viewModel: EpubReaderViewModel by viewModels()

    private lateinit var overlay: ComposeView

    // Assigned once the navigator fragment is committed; used to submit live pref updates
    // and apply TTS word decorations.
    private var navigatorFragment: EpubNavigatorFragment? = null

    companion object {
        // Public so ReaderScreen can build the arguments Bundle via AndroidFragment.
        // Direct instantiation is not supported — use AndroidFragment<EpubReaderFragment>
        // with arguments = bundleOf(ARG_BOOK_ID to bookId).
        const val ARG_BOOK_ID = "book_id"
        private const val TAG_NAVIGATOR = "epub_navigator"
        private const val TTS_DECORATION_GROUP = "tts"
        private val CONTAINER_ID get() = R.id.epub_reader_container
        // Amber at 40% alpha — accessible on both light and dark Readium themes.
        private val TTS_HIGHLIGHT_TINT = Color.argb(102, 255, 215, 0)
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
        setupNavigator(isConfigChange)
        setupTypographyObserver()
        setupTtsObserver()
    }

    // ── Private lifecycle helpers ─────────────────────────────────────────────

    private fun setupNavigator(isConfigChange: Boolean) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state
                    .filterIsInstance<EpubReaderViewModel.State.Ready>()
                    .take(1)
                    .collect { state ->
                        childFragmentManager.fragmentFactory =
                            state.navigatorFactory.createFragmentFactory(
                                initialLocator = state.initialLocator,
                                initialPreferences = state.initialTypography,
                            )
                        if (!isConfigChange) {
                            childFragmentManager.commit {
                                setReorderingAllowed(true)
                                replace(CONTAINER_ID, EpubNavigatorFragment::class.java, Bundle(), TAG_NAVIGATOR)
                            }
                            childFragmentManager.executePendingTransactions()
                        }
                        navigatorFragment =
                            childFragmentManager.findFragmentByTag(TAG_NAVIGATOR)
                                as? EpubNavigatorFragment
                        // Persist position on every page turn.
                        val bookId = arguments?.getString(ARG_BOOK_ID) ?: return@collect
                        val fragment = navigatorFragment ?: return@collect
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
                viewModel.ttsUiState.collect { state ->
                    val locator = (state as? TtsUiState.Active)?.tokenLocator
                    val decorations = if (locator != null) {
                        listOf(
                            Decoration(
                                id = "tts_word",
                                locator = locator,
                                style = Decoration.Style.Highlight(tint = TTS_HIGHLIGHT_TINT),
                            ),
                        )
                    } else {
                        emptyList()
                    }
                    navigatorFragment?.applyDecorations(decorations, TTS_DECORATION_GROUP)
                }
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
                    ReaderOverlay(
                        state = state,
                        typographyPrefs = typographyPrefs,
                        ttsUiState = ttsUiState,
                        ttsPrefs = ttsPrefs,
                        onBack = { activity?.onBackPressedDispatcher?.onBackPressed() },
                        onTypographyChange = viewModel::updateTypography,
                        onTtsPlay = { viewModel.startTts() },
                        onTtsPause = viewModel::pauseTts,
                        onTtsStop = viewModel::stopTts,
                        onTtsSpeedChange = viewModel::updateTtsSpeed,
                    )
                }
            }
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        return root
    }
}
