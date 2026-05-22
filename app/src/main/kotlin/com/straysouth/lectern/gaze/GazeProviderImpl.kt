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
    // Stored as a field so pauseAnalysis/resumeAnalysis can clear/restore the analyzer
    // without tearing down the CameraX binding or the GPU delegate.
    private var imageAnalysis: ImageAnalysis? = null
    private val filterX = OneEuroFilter(freq = 30.0)
    private val filterY = OneEuroFilter(freq = 30.0)

    override suspend fun start() {
        calibration = calibrationRepository.load()
        // No-calibration state is already represented by the initial
        // GazeState.Paused at the _state field declaration; no log needed.
        createLandmarker()
        bindCamera()
        registerThermalListener()
    }

    override suspend fun stop() {
        unregisterThermalListener()
        // Use the same suspendCancellableCoroutine pattern as bindCamera() — never block Main.
        val provider = suspendCancellableCoroutine { cont ->
            ProcessCameraProvider.getInstance(context).also { future ->
                future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
            }
        }
        withContext(Dispatchers.Main) { provider.unbindAll() }
        analysisExecutor.shutdown()
        landmarker?.close()
        landmarker = null
        imageAnalysis = null
        _state.value = GazeState.Paused
        scope.cancel()
    }

    override fun pauseAnalysis() {
        // clearAnalyzer() is thread-safe per CameraX contract. Camera stays bound;
        // GPU delegate stays warm — resume is ~0 ms with no re-init cost.
        imageAnalysis?.clearAnalyzer()
        _state.value = GazeState.Paused
    }

    override fun resumeAnalysis() {
        // Re-attach analyzer; state updates to Tracking on next analyzed frame.
        imageAnalysis?.setAnalyzer(analysisExecutor, ::analyzeFrame)
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
            // LOO-CV: generalisation error estimate rather than trivially-low in-sample residual.
            val meanErrorPx = computeLooMeanErrorPx(n, xMat, yVecX, yVecY, points)
            val result = CalibrationResult(
                weightsX = DoubleArray(FEATURE_COUNT) { wx.get(it, 0) },
                weightsY = DoubleArray(FEATURE_COUNT) { wy.get(it, 0) },
                meanErrorPx = meanErrorPx,
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
        imageAnalysis = analysis
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
            // Pass raw irisU/V alongside screen point — CalibrationScreen reads them
            // as regression inputs. Passing gazePoint instead would corrupt calibration.
            _state.value = GazeState.Tracking(PointF(smoothX, smoothY), u.toFloat(), v.toFloat())
        }
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
                    // clearAnalyzer stops frame delivery and CPU/GPU inference load.
                    // State-only change was insufficient — frames kept processing.
                    pauseAnalysis()
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

    private fun ridge(x: DMatrixRMaj, y: DMatrixRMaj, lambda: Double): DMatrixRMaj {
        val p = x.numCols
        val xt = DMatrixRMaj(p, x.numRows).also { CommonOps_DDRM.transpose(x, it) }
        val xtx = DMatrixRMaj(p, p).also { CommonOps_DDRM.mult(xt, x, it) }
        for (i in 0 until p) xtx.add(i, i, lambda)
        val xty = DMatrixRMaj(p, 1).also { CommonOps_DDRM.mult(xt, y, it) }
        val w = DMatrixRMaj(p, 1)
        val solver = LinearSolverFactory_DDRM.symmPosDef(p)
        check(solver.setA(xtx)) { "Ridge: matrix not positive definite — degenerate calibration data" }
        solver.solve(xty, w)
        return w
    }

    /**
     * Leave-one-out cross-validation mean Euclidean error in screen pixels.
     *
     * For each point: fits ridge on the other n-1, predicts the left-out point, measures
     * Euclidean distance to the true screen position. Average across all n folds is a
     * generalisation error estimate — unlike in-sample residuals it is not trivially small
     * when the model nearly interpolates its training data (which ridge does at n=9, p=6).
     *
     * Guard: LOO requires n-1 >= FEATURE_COUNT (positive-definite XtX). Falls back to
     * in-sample only when n == FEATURE_COUNT — unreachable in production (UI collects 9).
     */
    private fun computeLooMeanErrorPx(
        n: Int,
        xMat: DMatrixRMaj,
        yVecX: DMatrixRMaj,
        yVecY: DMatrixRMaj,
        points: List<CalibrationPoint>,
    ): Float {
        if (n <= FEATURE_COUNT) {
            // Unreachable in production; in-sample fallback avoids underdetermined LOO solve.
            val wx = ridge(xMat, yVecX, RIDGE_LAMBDA)
            val wy = ridge(xMat, yVecY, RIDGE_LAMBDA)
            val predX = DMatrixRMaj(n, 1)
            val predY = DMatrixRMaj(n, 1)
            CommonOps_DDRM.mult(xMat, wx, predX)
            CommonOps_DDRM.mult(xMat, wy, predY)
            return (0 until n).map { i ->
                val dx = predX.get(i, 0) - yVecX.get(i, 0)
                val dy = predY.get(i, 0) - yVecY.get(i, 0)
                kotlin.math.sqrt(dx * dx + dy * dy)
            }.average().toFloat()
        }
        return (0 until n).map { leaveOut ->
            val xTrain = DMatrixRMaj(n - 1, FEATURE_COUNT)
            val yTrainX = DMatrixRMaj(n - 1, 1)
            val yTrainY = DMatrixRMaj(n - 1, 1)
            var row = 0
            for (i in 0 until n) {
                if (i == leaveOut) continue
                for (j in 0 until FEATURE_COUNT) xTrain.set(row, j, xMat.get(i, j))
                yTrainX.set(row, 0, yVecX.get(i, 0))
                yTrainY.set(row, 0, yVecY.get(i, 0))
                row++
            }
            val wxLoo = ridge(xTrain, yTrainX, RIDGE_LAMBDA)
            val wyLoo = ridge(xTrain, yTrainY, RIDGE_LAMBDA)
            val features = featureVector(points[leaveOut].irisU.toDouble(), points[leaveOut].irisV.toDouble())
            val pX = (0 until FEATURE_COUNT).sumOf { j -> features[j] * wxLoo.get(j, 0) }
            val pY = (0 until FEATURE_COUNT).sumOf { j -> features[j] * wyLoo.get(j, 0) }
            val dx = pX - points[leaveOut].screenX.toDouble()
            val dy = pY - points[leaveOut].screenY.toDouble()
            kotlin.math.sqrt(dx * dx + dy * dy)
        }.average().toFloat()
    }
}

// featureVector is stateless — kept top-level so GazeProviderImpl stays under
// the TooManyFunctions threshold while still accessible from all class methods.
private fun featureVector(u: Double, v: Double): DoubleArray =
    doubleArrayOf(u, v, u * u, v * v, u * v, 1.0)
