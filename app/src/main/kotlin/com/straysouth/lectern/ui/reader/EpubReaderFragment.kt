package com.straysouth.lectern.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.straysouth.lectern.R
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment

class EpubReaderFragment : Fragment() {

    private val viewModel: EpubReaderViewModel by viewModels()

    private var navigatorFragment: EpubNavigatorFragment? = null

    companion object {
        private const val ARG_BOOK_ID = "book_id"
        private const val ARG_FILE_URI = "file_uri"
        private const val TAG_NAVIGATOR = "epub_navigator"
        private val CONTAINER_ID get() = R.id.epub_reader_container

        fun newInstance(bookId: String, fileUri: String) = EpubReaderFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_BOOK_ID, bookId)
                putString(ARG_FILE_URI, fileUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val bookId = arguments?.getString(ARG_BOOK_ID)
        val fileUri = arguments?.getString(ARG_FILE_URI)

        // Dummy factory must be set before super.onCreate() so the FragmentManager
        // can safely restore EpubNavigatorFragment on config change or process death.
        // The real factory is installed below once the publication is loaded.
        childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)

        if (bookId == null || fileUri == null) {
            requireActivity().finish()
            return
        }

        viewModel.load(bookId, fileUri)

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
                            )
                        if (childFragmentManager.findFragmentByTag(TAG_NAVIGATOR) == null) {
                            childFragmentManager.commit {
                                setReorderingAllowed(true)
                                add(CONTAINER_ID, EpubNavigatorFragment::class.java, Bundle(), TAG_NAVIGATOR)
                            }
                        }
                    }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = CONTAINER_ID
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navigatorFragment =
            childFragmentManager.findFragmentByTag(TAG_NAVIGATOR) as? EpubNavigatorFragment
    }
}
