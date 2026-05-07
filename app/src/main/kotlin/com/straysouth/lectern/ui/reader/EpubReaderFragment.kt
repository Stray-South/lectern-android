package com.straysouth.lectern.ui.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.straysouth.lectern.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
        val bookId = arguments?.getString(ARG_BOOK_ID) ?: run {
            // Process death with no args — factory placeholder prevents crash on restore
            childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            requireActivity().finish()
            return
        }
        val fileUri = arguments?.getString(ARG_FILE_URI) ?: run {
            childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            requireActivity().finish()
            return
        }

        // Factory must be set before super.onCreate() so the fragment manager
        // can restore EpubNavigatorFragment on config change / process death
        childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)

        viewModel.load(bookId, fileUri)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.state.collect { state ->
                    if (state is EpubReaderViewModel.State.Ready) {
                        childFragmentManager.fragmentFactory =
                            state.navigatorFactory.createFragmentFactory(
                                initialLocator = state.initialLocator,
                            )
                        if (childFragmentManager.findFragmentByTag(TAG_NAVIGATOR) == null) {
                            childFragmentManager.beginTransaction()
                                .add(CONTAINER_ID, EpubNavigatorFragment::class.java, Bundle(), TAG_NAVIGATOR)
                                .commitNow()
                        }
                        navigatorFragment =
                            childFragmentManager.findFragmentByTag(TAG_NAVIGATOR) as? EpubNavigatorFragment
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
}
