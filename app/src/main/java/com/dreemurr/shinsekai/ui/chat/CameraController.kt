package com.dreemurr.shinsekai.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

// 视频聊天摄像头控制器：前置预览 + 灰度网格帧差 + 拍帧；触发信号与发送策略由 ChatViewModel 决定。
class CameraController(private val context: Context) {

    // 变化阈值（差异格子比例 0..1），由设置流实时更新；跨线程读。
    @Volatile
    var changeThreshold: Float = 0.2f

    // 预览视图：小窗与全屏共用同一实例，点击互换时由 Compose 重新布局。
    val previewView: PreviewView by lazy { PreviewView(context) }

    // 自动触发信号；分析线程 tryEmit（DROP_OLDEST 不丢最新），主线程收集。
    val triggers = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var imageCapture: ImageCapture? = null

    // stop 后 getInstance 回调才到达时不再绑定，避免把摄像头挂到已销毁的 lifecycle。
    @Volatile
    private var stopped = false

    private var provider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 帧差状态：基准网格与连续命中数仅分析线程读写；重置经 volatile 引用换新。
    @Volatile
    private var baselineGrid: FloatArray? = null

    @Volatile
    private var consecutiveHits = 0

    private var lastSampleAtMillis = 0L

    // 必须在主线程调用；绑定前置 Preview + ImageAnalysis + ImageCapture。
    fun start(owner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (stopped) return@addListener
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }
                    // 拍帧分辨率交由系统默认选择；转码在 Default 调度器执行，不阻塞主线程。
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analysis,
                        capture,
                    )
                    imageCapture = capture
                    // 重绑（旋转等）后画面方向/构图变化大，重置基准避免误触发一次自动发帧。
                    resetBaseline()
                } catch (e: Exception) {
                    // 无前置摄像头等极端情况：预览与拍帧不可用，不影响其余会话功能。
                    e.printStackTrace()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    // 解绑并释放线程；离开聊天页 / VM 销毁时调用。
    fun stop() {
        stopped = true
        runCatching { provider?.unbindAll() }
        provider = null
        imageCapture = null
        baselineGrid = null
        consecutiveHits = 0
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    // 发送帧后重置基准：下一分析帧重新校准为当前画面。
    fun resetBaseline() {
        baselineGrid = null
        consecutiveHits = 0
    }

    // 拍当前帧：旋转竖直、短边缩到 640、JPEG 压缩，不镜像；转码在 Default 调度器不阻塞主线程。
    suspend fun captureJpeg(): ByteArray? {
        val capture = imageCapture ?: return null
        val proxy = suspendCancellableCoroutine<ImageProxy?> { cont ->
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (cont.isActive) cont.resumeWith(Result.success(image))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        exception.printStackTrace()
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                },
            )
        } ?: return null
        return withContext(Dispatchers.Default) {
            proxy.use { image ->
                val source = image.toBitmap()
                val rotated = rotateBitmap(source, image.imageInfo.rotationDegrees)
                if (rotated != source) source.recycle()
                val scaled = scaleShortSide(rotated, TARGET_SHORT_SIDE)
                if (scaled != rotated) rotated.recycle()
                val output = ByteArrayOutputStream(JPEG_BUFFER_HINT)
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                scaled.recycle()
                output.toByteArray()
            }
        }
    }

    // 帧差分析：Y 平面降采样为灰度网格，与基准比对；连续两次超阈值才触发，防光照抖动误报。
    private fun analyzeFrame(image: ImageProxy) {
        image.use { proxy ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSampleAtMillis < SAMPLE_INTERVAL_MILLIS) return
            lastSampleAtMillis = now
            val grid = lumaGrid(proxy)
            val baseline = baselineGrid
            if (baseline == null) {
                // 启动/重置后的首帧仅校准基准，不判定。
                baselineGrid = grid
                return
            }
            if (changedCellRatio(baseline, grid) >= changeThreshold) {
                consecutiveHits++
                if (consecutiveHits >= CONFIRM_COUNT) {
                    consecutiveHits = 0
                    triggers.tryEmit(Unit)
                }
            } else {
                consecutiveHits = 0
            }
        }
    }

    // Y（亮度）平面稀疏采样为 GRID_COLS x GRID_ROWS 的均值网格。
    private fun lumaGrid(image: ImageProxy): FloatArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        // 上界只取一次，内层循环避免重复调用。
        val capacity = buffer.capacity()
        val grid = FloatArray(GRID_COLS * GRID_ROWS)
        for (gy in 0 until GRID_ROWS) {
            val y0 = gy * height / GRID_ROWS
            val y1 = (gy + 1) * height / GRID_ROWS
            val stepY = maxOf(1, (y1 - y0) / SAMPLE_POINTS)
            for (gx in 0 until GRID_COLS) {
                val x0 = gx * width / GRID_COLS
                val x1 = (gx + 1) * width / GRID_COLS
                val stepX = maxOf(1, (x1 - x0) / SAMPLE_POINTS)
                var sum = 0L
                var count = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val index = y * rowStride + x * pixelStride
                        if (index < capacity) {
                            sum += buffer.get(index).toInt() and 0xFF
                            count++
                        }
                        x += stepX
                    }
                    y += stepY
                }
                grid[gy * GRID_COLS + gx] = if (count > 0) sum.toFloat() / count else 0f
            }
        }
        return grid
    }

    private fun changedCellRatio(baseline: FloatArray, current: FloatArray): Float {
        var changed = 0
        for (i in baseline.indices) {
            if (abs(baseline[i] - current[i]) > CELL_DELTA) changed++
        }
        return changed.toFloat() / baseline.size
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleShortSide(bitmap: Bitmap, target: Int): Bitmap {
        val short = minOf(bitmap.width, bitmap.height)
        if (short <= target) return bitmap
        val scale = target.toFloat() / short
        return bitmap.scale(
            (bitmap.width * scale).roundToInt(),
            (bitmap.height * scale).roundToInt(),
        )
    }

    companion object {
        private const val GRID_COLS = 16
        private const val GRID_ROWS = 12
        // 单格亮度差超过该值视为变化（0..255）。
        private const val CELL_DELTA = 18f
        // 连续命中该次数才触发，规避瞬时光照抖动。
        private const val CONFIRM_COUNT = 2
        private const val SAMPLE_INTERVAL_MILLIS = 400L
        // 每格稀疏采样点数（每边）。
        private const val SAMPLE_POINTS = 8
        private const val TARGET_SHORT_SIDE = 640
        private const val JPEG_QUALITY = 70
        // JPEG 输出缓冲初始容量预估（短边 640、质量 70 约几十 KB）。
        private const val JPEG_BUFFER_HINT = 64 * 1024
    }
}
