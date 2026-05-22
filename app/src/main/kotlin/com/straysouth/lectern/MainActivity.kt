package com.straysouth.lectern

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import com.straysouth.lectern.data.db.Book
import com.straysouth.lectern.data.repository.CalibrationRepository
import com.straysouth.lectern.ui.gaze.CALIBRATION_TOTAL_POINTS
import com.straysouth.lectern.ui.gaze.CalibrationScreen
import com.straysouth.lectern.ui.gaze.CalibrationUiState
import com.straysouth.lectern.ui.gaze.GazeViewModel
import com.straysouth.lectern.ui.gaze.GazeViewModelFactory
import com.straysouth.lectern.ui.library.LibraryScreen
import com.straysouth.lectern.ui.library.LibraryViewModel
import com.straysouth.lectern.ui.reader.ReaderScreen
import com.straysouth.lectern.ui.theme.LecternTheme
import com.straysouth.lectern.ui.window.LocalWindowSecurityController
import com.straysouth.lectern.ui.window.WindowSecurityController
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

class MainActivity : AppCompatActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()
    private val gazeViewModel: GazeViewModel by viewModels {
        GazeViewModelFactory(application, CalibrationRepository(applicationContext))
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> gazeViewModel.onPermissionResult(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gazeViewModel.attachLifecycleOwner(this)
        enableEdgeToEdge()
        setContent {
            // V2 infrastructure: WindowSecurityController bound at Activity scope.
            // Sensitive Composables (annotations reader, future DRM/auth/foreground-
            // notification surfaces per ADR-AND-R V2 reconsideration triggers) call
            // SecureWindow() to claim FLAG_SECURE; the reference counter keeps the
            // flag set while any sensitive screen is in composition.
            // See docs/plans/v2-scope.md §Cross-cutting risk register.
            val windowSecurityController = remember { WindowSecurityController(window) }
            CompositionLocalProvider(
                LocalWindowSecurityController provides windowSecurityController,
            ) {
            LecternTheme {
                val cdApp = stringResource(R.string.cd_app)
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = cdApp },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GazePermissionEffect(gazeViewModel) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    AppContent(libraryViewModel, gazeViewModel, ::hasCameraPermission)
                }
            }
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
private fun GazePermissionEffect(vm: GazeViewModel, launchRequest: () -> Unit) {
    val needsPermission by vm.needsPermission.collectAsState()
    LaunchedEffect(needsPermission) {
        if (needsPermission) launchRequest()
    }
}

@Composable
private fun AppContent(
    libraryViewModel: LibraryViewModel,
    gazeViewModel: GazeViewModel,
    hasCameraPermission: () -> Boolean,
) {
    var currentBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentBookFormat by rememberSaveable { mutableStateOf<String?>(null) }
    val calibrationUiState by gazeViewModel.calibrationUiState.collectAsState()
    val gazeState by gazeViewModel.gazeState.collectAsState()

    // Navigate back to library if the currently-open book is deleted.
    // acknowledgeDeletedBook() clears the replay cache so re-importing the same
    // URI does not spuriously close the newly-opened reader.
    LaunchedEffect(Unit) {
        libraryViewModel.deletedBookId.collect { deletedId ->
            if (currentBookId == deletedId) {
                currentBookId = null
                currentBookFormat = null
            }
            libraryViewModel.acknowledgeDeletedBook()
        }
    }

    BackHandler(enabled = currentBookId != null) {
        currentBookId = null
        currentBookFormat = null
    }

    val bookId = currentBookId
    if (bookId == null) {
        LibraryScreen(
            viewModel = libraryViewModel,
            onBookSelected = { book: Book ->
                currentBookId = book.id
                currentBookFormat = book.format
                libraryViewModel.recordOpened(book.id)
            },
        )
    } else {
        ReaderScreen(bookId = bookId, format = currentBookFormat ?: "EPUB")
        // Wire gaze toggle permission check — Fragment calls toggleGaze(hasPermission)
        // directly; needsPermission StateFlow triggers the launcher above.
        LaunchedEffect(Unit) {
            gazeViewModel.needsPermission.collect { needs ->
                if (needs && hasCameraPermission()) {
                    gazeViewModel.onPermissionResult(true)
                }
            }
        }
    }

    // CalibrationScreen is a full-screen surface (alpha=0.95) that overlays whatever is
    // below. Placed at Activity setContent level so positionInRoot() measurements span the
    // full edge-to-edge window — same coordinate space as the GazeFocusBandOverlay.
    if (calibrationUiState !is CalibrationUiState.Idle) {
        // LIFO BackHandler: this is composed after the reader BackHandler above, so it wins
        // when both are active. The reader handler uses enabled = (currentBookId != null),
        // which is false when calibration is launched from the library — correct in both cases.
        BackHandler { gazeViewModel.cancelCalibration() }
        CalibrationScreen(
            state = calibrationUiState,
            gazeState = gazeState,
            onRecordPoint = { point ->
                gazeViewModel.recordCalibrationPoint(point, CALIBRATION_TOTAL_POINTS)
            },
            onCancel = { gazeViewModel.cancelCalibration() },
            onRecalibrate = { gazeViewModel.startCalibration(CALIBRATION_TOTAL_POINTS) },
        )
    }
}
