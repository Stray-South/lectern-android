package com.straysouth.lectern.ui.reader

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
import org.readium.r2.navigator.epub.EpubNavigatorFragment

class EpubReaderFragment : Fragment() {

    private val viewModel: EpubReaderViewModel by viewModels()

    private lateinit var overlay: ComposeView

    // Assigned once the navigator fragment is committed; used to submit live pref updates.
    private var navigatorFragment: EpubNavigatorFragment? = null

    companion object {
        // Public so ReaderScreen can build the arguments Bundle via AndroidFragment.
        // Direct instantiation is not supported — use AndroidFragment<EpubReaderFragment>
        // with arguments = bundleOf(ARG_BOOK_ID to bookId).
        const val ARG_BOOK_ID = "book_id"
        private const val TAG_NAVIGATOR = "epub_navigator"
        private val CONTAINER_ID get() = R.id.epub_reader_container
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val bookId = arguments?.getString(ARG_BOOK_ID)

        // Dummy factory must be set before super.onCreate() so the FragmentManager
        // can safely restore EpubNavigatorFragment on config change or process death.
        // The real factory is installed below once the publication is loaded.
        childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)

        if (bookId == null) {
            // Null bookId is a programming error — AndroidFragment should always supply it.
            // Leave the Fragment inert; BackHandler in MainActivity handles navigation back.
            return
        }

        viewModel.load(bookId)

        // STARTED guarantees mStateSaved and mStopped are both false — safe for commit().
        // take(1) makes this a one-shot action; the findFragmentByTag guard prevents
        // double-add if STARTED restarts (e.g. returning from back stack).
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
                        if (childFragmentManager.findFragmentByTag(TAG_NAVIGATOR) == null) {
                            childFragmentManager.commit {
                                setReorderingAllowed(true)
                                add(CONTAINER_ID, EpubNavigatorFragment::class.java, Bundle(), TAG_NAVIGATOR)
                            }
                            childFragmentManager.executePendingTransactions()
                        }
                        navigatorFragment =
                            childFragmentManager.findFragmentByTag(TAG_NAVIGATOR)
                                as? EpubNavigatorFragment
                    }
            }
        }

        // Push live typography changes to the navigator. navigatorFragment is null until
        // the Ready collector above fires, so early emissions are no-ops — that is correct
        // because initialTypography already bakes the startup prefs into the factory.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.typographyPrefs.collect { prefs ->
                    navigatorFragment?.submitPreferences(prefs.toEpubPreferences())
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
                    ReaderOverlay(
                        state = state,
                        typographyPrefs = typographyPrefs,
                        onBack = { activity?.onBackPressedDispatcher?.onBackPressed() },
                        onTypographyChange = viewModel::updateTypography,
                    )
                }
            }
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        return root
    }
}
