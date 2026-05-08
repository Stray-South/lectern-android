package com.straysouth.lectern.gaze

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.straysouth.lectern.data.repository.CalibrationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.ejml.data.DMatrixRMaj
import org.ejml.dense.row.CommonOps_DDRM
import org.ejml.dense.row.factory.LinearSolverFactory_DDRM
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "GazeProviderImpl"
private const val IRIS_LEFT_INDEX = 468
private const val IRIS_RIGHT_INDEX = 473
private const val FEATURE_COUNT = 6  // [u, v, u², v², uv, 1]
private const val RIDGE_LAMBDA = 1e-4

class GazeProviderImpl(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val calibrationRepository: CalibrationRepository,
) : GazeProvider {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)

    private val _state = MutableStateFlow<GazeState>(GazeState.Paused)
    override val gazeState: StateFlow<GazeState> = _state.asStateFlow()

    private var landmarker: FaceLandmarker? = null
    private var calibration: CalibrationResult? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val filterX = OneEuroFilter(freq = 30.0)
    private val filterY = OneEuroFilter(freq = 30.0)

    override suspend fun start() {
        calibration = calibrationRepository.load()
        if (calibration == null) {
            Log.d(TAG, "No calibration found — gaze stays Paused until calibrated.")
        }
        createLandmarker()
        bindCamera()
        registerThermalListener()
    }

    override suspend fun stop() {
        unregisterThermalListener()
        withContext(Dispatchers.Main) {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
        landmarker?.close()
        landmarker = null
        _state.value = GazeState.Paused
        scope.cancel()
    }

    /**
     * Fits ridge regression models from calibration points.
     * Must be called with at least FEATURE_COUNT points (9 recommended).
     */
    override suspend fun calibrate(points: List<CalibrationPoint>): CalibrationResult =
        withContext(confined) {
            val n = points.size
            require(n >= FEATURE_COUNT) {
                "Need at least $FEATURE_COUNT calibration points, got $n"
            }
            val xMat = DMatrixRMaj(n, FEATURE_COUNT)
            val yVecX = DMatrixRMaj(n, 1)
            val yVecY = DMatrixRMaj(n, 1)
            points.forEachIndexed { i, p ->
                val features = featureVector(p.irisU.toDouble(), p.irisV.toDouble())
                features.forEachIndexed { j, v -> xMat.set(i, j, v) }
                yVecX.set(i, 0, p.screenX.toDouble())
                yVecY.set(i, 0, p.screenY.toDouble())
            }
            val wx = ridge(xMat, yVecX, RIDGE_LAMBDA)
            val wy = ridge(xMat, yVecY, RIDGE_LAMBDA)
            val result = CalibrationResult(
                weightsX = DoubleArray(FEATURE_COUNT) { wx.get(it, 0) },
                weightsY = DoubleArray(FEATURE_COUNT) { wy.get(it, 0) },
            )
            calibration = result
            calibrationRepository.save(result)
            filterX.reset()
            filterY.reset()
            result
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun createLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.GPU)
            .setModelAssetPath("face_landmarker.task")
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onLandmarkerResult(result) }
            .setErrorListener { e -> Log.e(TAG, "FaceLandmarker error", e) }
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)
    }

    private suspend fun bindCamera() {
        val provider = suspendCancellableCoroutine { cont ->
            ProcessCameraProvider.getInstance(context).also { future ->
                future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
            }
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // RGBA_8888 required by BitmapImageBuilder — YUV_420_888 (default) throws
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }
        withContext(Dispatchers.Main) {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                analysis,
            )
        }
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        // proxy.use {} guarantees close() on all exit paths — prevents CameraX pipeline stall.
        proxy.use { p ->
            val lm = landmarker ?: return
            val bmp = p.toBitmap()
            val mpImage = BitmapImageBuilder(bmp).build()
            // SystemClock.uptimeMillis() is monotonically increasing — never use
            // System.currentTimeMillis() which can go backwards on NTP sync.
            lm.detectAsync(mpImage, SystemClock.uptimeMillis())
        }
    }

    private fun onLandmarkerResult(result: FaceLandmarkerResult) {
        // Callback arrives on MediaPipe's own thread — post to confined dispatcher.
        scope.launch {
            val landmarks = result.faceLandmarks().firstOrNull()
            if (landmarks == null) {
                _state.value = GazeState.NoFace
                return@launch
            }
            val cal = calibration ?: run {
                _state.value = GazeState.Paused
                return@launch
            }
            // Average both iris centers — avoids left/right mirroring ambiguity (ADR-AND-L).
            val left = landmarks[IRIS_LEFT_INDEX]
            val right = landmarks[IRIS_RIGHT_INDEX]
            val u = ((left.x() + right.x()) / 2.0)
            val v = ((left.y() + right.y()) / 2.0)

            val features = featureVector(u, v)
            val rawX = features.zip(cal.weightsX.toList()).sumOf { (f, w) -> f * w }
            val rawY = features.zip(cal.weightsY.toList()).sumOf { (f, w) -> f * w }

            val smoothX = filterX.filter(rawX).toFloat()
            val smoothY = filterY.filter(rawY).toFloat()
            _state.value = GazeState.Tracking(PointF(smoothX, smoothY))
        }
    }

    private fun featureVector(u: Double, v: Double): DoubleArray =
        doubleArrayOf(u, v, u * u, v * v, u * v, 1.0)

    private fun ridge(x: DMatrixRMaj, y: DMatrixRMaj, lambda: Double): DMatrixRMaj {
        val p = x.numCols
        val xt = DMatrixRMaj(p, x.numRows).also { CommonOps_DDRM.transpose(x, it) }
        val xtx = DMatrixRMaj(p, p).also { CommonOps_DDRM.mult(xt, x, it) }
        for (i in 0 until p) xtx.add(i, i, lambda)
        val xty = DMatrixRMaj(p, 1).also { CommonOps_DDRM.mult(xt, y, it) }
        val w = DMatrixRMaj(p, 1)
        val solver = LinearSolverFactory_DDRM.symmPosDef(p)
        requireNotNull(solver.setA(xtx).takeIf { it }) { "Ridge: singular regularized matrix" }
        solver.solve(xty, w)
        return w
    }

    // ── Thermal management (API 29+ only) ────────────────────────────────────

    private val thermalListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status ->
            when (status) {
                PowerManager.THERMAL_STATUS_SEVERE,
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                    Log.w(TAG, "Thermal status $status — pausing gaze analysis")
                    _state.value = GazeState.Paused
                }
                else -> { /* allow analysis to continue */ }
            }
        }
    } else null

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                pm.addThermalStatusListener(it)
            }
        }
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                pm.removeThermalStatusListener(it)
            }
        }
    }
}
