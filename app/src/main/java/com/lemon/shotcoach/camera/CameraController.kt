package com.lemon.shotcoach.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.lemon.shotcoach.analysis.FrameAnalyzer
import com.lemon.shotcoach.analysis.analyzer.OrientationAnalyzer
import com.lemon.shotcoach.model.UiState
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 封装。职责边界：只负责「取帧 → 转 Mat → 交给 [FrameAnalyzer] → 推 UiState」，
 * 以及把 [CameraParams] 分发给 [CameraParamController] / [ImageCapture]。
 * 不含任何视觉算法、不含任何 UI。
 *
 * 关键点（见 docs/ARCHITECTURE.md §2）：
 *  - ImageAnalysis 使用**独立单线程 Executor**，与预览渲染分离；
 *  - `STRATEGY_KEEP_ONLY_LATEST` + [FrameThrottler] 双重防积压；
 *  - 镜头或画幅变化才重建 use case（CameraX 不允许在已绑定的用例上改这两项），
 *    其余参数走 [CameraParamController] 热更新 —— 调滑杆不会重新开相机。
 */
class CameraController(
    private val context: Context,
    private val analyzer: FrameAnalyzer,
    private val orientation: OrientationAnalyzer,
    private val onState: (UiState) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    /**
     * 取景帧回调（约 3Hz，最长边约 360px）。
     *
     * 用途：滤镜面板的实时预览与缩略图底色。刻意**低频 + 低分辨率** ——
     * 滤镜预览不需要 30fps，但需要"所见即所得"；3Hz 已经足够，
     * 而且这条路走 CPU，不引入 OpenGL 管线。
     */
    private val onFrame: ((Bitmap) -> Unit)? = null,
) {
    /** 供 [CaptureController] 绑定；每次重建 use case 后刷新 */
    var imageCapture: ImageCapture? = null
        private set

    /** 当前已绑定的 Camera，供参数下发使用 */
    var camera: Camera? = null
        private set

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shotcoach-analysis").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    private val paramController = CameraParamController()

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null

    /** 复用的 RGBA Mat（NFR-2） */
    private val rgbaMat = Mat()

    // 取景帧回调节流：每 10 帧一次（30fps 下约 3Hz）
    private val frameThrottler = FrameThrottler(10)
    private var frameSink: Bitmap? = null

    // 已绑定的选择（用于判断是否需要重建 use case）
    private var boundLensFacing: Int = CameraParams.LENS_FACING_BACK
    private var boundAspect: AspectRatio = AspectRatio.RATIO_4_3

    /** 最近一次下发结果，UI 可读来纠正滑杆（如硬件钳制了 EV / 不支持手动 ISO） */
    var lastApplied: CameraParamController.Applied? = null
        private set

    // ── 生命周期 ──────────────────────────────────────────────

    /** 首次绑定。重复调用是安全的。 */
    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner, params: CameraParams) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        ensureProvider { bindUseCases(params, force = true) }
    }

    /**
     * 参数变化入口。
     * 只有**镜头朝向**或**画幅比例**变化时才重建 use case，其余走热更新。
     */
    @SuppressLint("UnsafeOptinUsageError")
    fun updateParams(params: CameraParams) {
        val needRebind = params.lensFacing != boundLensFacing || params.aspect != boundAspect
        if (needRebind) {
            bindUseCases(params, force = true)
        } else {
            camera?.let { cam -> lastApplied = paramController.apply(cam, params) }
        }
    }

    private fun ensureProvider(onReady: () -> Unit) {
        if (cameraProvider != null) {
            onReady()
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                onReady()
            } catch (t: Throwable) {
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptinUsageError")
    private fun bindUseCases(params: CameraParams, force: Boolean) {
        val provider = cameraProvider ?: return
        val pv = previewView ?: return
        val owner = lifecycleOwner ?: return

        if (!force && params.lensFacing == boundLensFacing && params.aspect == boundAspect) {
            camera?.let { lastApplied = paramController.apply(it, params) }
            return
        }

        try {
            val targetRatio = params.aspect.toCameraXAspectOrNull()

            val preview = Preview.Builder().apply {
                if (targetRatio != null) setTargetAspectRatio(targetRatio)
            }.build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }

            val capture = ImageCapture.Builder()
                .apply { if (targetRatio != null) setTargetAspectRatio(targetRatio) }
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // 分析分辨率：640×480 足够（内部还会再降到 224）
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) }

            val selector = CameraSelector.Builder()
                .requireLensFacing(params.lensFacing)
                .build()

            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, selector, preview, capture, analysis)

            boundLensFacing = params.lensFacing
            boundAspect = params.aspect
            imageCapture = capture

            // 绑定后立刻下发一次全部参数（EV/变焦/ISO/快门/白平衡/对焦/闪光/常亮）
            camera?.let { cam ->
                capture.flashMode = params.flash.toImageCaptureFlashMode()
                lastApplied = paramController.apply(cam, params)
            }
        } catch (t: Throwable) {
            onError(t)
        }
    }

    // ── 取帧 ─────────────────────────────────────────────────

    private fun analyzeFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        try {
            // ImageProxy(YUV_420_888/RGBA) → Mat
            val src = proxyToMat(proxy)
            val state = analyzer.analyze(src, orientation.rollDegrees(), now)
            if (state != null) onState(state)

            // 取景帧回调（低频、低分辨率），供滤镜预览
            if (onFrame != null && frameThrottler.shouldAnalyze()) {
                emitFrame(proxy.width, proxy.height)
            }
        } catch (t: Throwable) {
            onError(t)
        } finally {
            proxy.close()   // 必须在 finally 关闭，否则丢帧卡死
        }
    }

    /**
     * 把当前帧缩到最长边 [FRAME_SINK_MAX] 交给 [onFrame]。
     *
     * 复用同一个 Bitmap 与 Canvas，避免 3Hz 也产生 GC 压力。
     */
    private fun emitFrame(srcW: Int, srcH: Int) {
        val sink = onFrame ?: return
        try {
            val full = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbaMat, full)

            val scale = FRAME_SINK_MAX.toFloat() / maxOf(srcW, srcH).toFloat()
            val outW = (srcW * scale).toInt().coerceAtLeast(1)
            val outH = (srcH * scale).toInt().coerceAtLeast(1)

            val reuse = frameSink
            val target = if (reuse != null && reuse.width == outW && reuse.height == outH && !reuse.isRecycled) {
                reuse
            } else {
                frameSink?.recycle()
                Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { frameSink = it }
            }

            Canvas(target).drawBitmap(full, Rect(0, 0, srcW, srcH), Rect(0, 0, outW, outH), null)
            full.recycle()
            sink(target)
        } catch (t: Throwable) {
            // 预览帧失败不应影响主流程
        }
    }

    /**
     * ImageProxy → RGBA Mat。
     *
     * RGBA_8888 输出时，plane[0] 就是 packed RGBA，`Utils.matToBitmap` 可直接使用。
     * 这里刻意不转 Bitmap（少一次拷贝），直接把 plane 拷进 Mat。
     */
    private fun proxyToMat(proxy: ImageProxy): Mat {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        if (rgbaMat.empty() || rgbaMat.cols() != proxy.width || rgbaMat.rows() != proxy.height) {
            rgbaMat.create(proxy.height, proxy.width, org.opencv.core.CvType.CV_8UC4)
        }

        // rowStride 可能大于 width*4（对齐填充），需逐行拷贝
        val rowStride = plane.rowStride
        val rowBytes = proxy.width * 4
        if (rowStride == rowBytes) {
            rgbaMat.put(0, 0, bytes)
        } else {
            val row = ByteArray(rowBytes)
            for (y in 0 until proxy.height) {
                System.arraycopy(bytes, y * rowStride, row, 0, rowBytes)
                rgbaMat.put(y, 0, row)
            }
        }
        return rgbaMat
    }

    // ── 释放 ─────────────────────────────────────────────────

    fun unbind() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
    }

    fun shutdown() {
        unbind()
        analysisExecutor.shutdown()
        frameSink?.recycle()
        frameSink = null
        rgbaMat.release()
    }

    /** 供测试：任意 Bitmap → Mat（RGBA） */
    fun bitmapToRgba(bitmap: Bitmap): Mat {
        val m = Mat()
        Utils.bitmapToMat(bitmap, m)
        if (m.channels() == 3) Imgproc.cvtColor(m, m, Imgproc.COLOR_RGB2RGBA)
        return m
    }

    companion object {
        /** 取景帧回调的最长边像素 */
        const val FRAME_SINK_MAX = 360
    }
}

// ── 映射工具 ────────────────────────────────────────────────

/**
 * Maps the app aspect ratio to CameraX [androidx.camera.core.AspectRatio].
 *
 * CameraX only accepts 4:3 and 16:9. 1:1 and 3:4 return null so the
 * use case stays on the default 4:3 sensor crop; [com.lemon.shotcoach.render.PhotoProcessor]
 * center-crops the captured bitmap to the requested ratio.
 */
internal fun AspectRatio.toCameraXAspectOrNull(): Int? = when (this) {
    AspectRatio.RATIO_4_3 -> androidx.camera.core.AspectRatio.RATIO_4_3
    AspectRatio.RATIO_16_9 -> androidx.camera.core.AspectRatio.RATIO_16_9
    AspectRatio.RATIO_1_1, AspectRatio.RATIO_3_4 -> null
}

internal fun FlashMode.toImageCaptureFlashMode(): Int = when (this) {
    FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
    FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    FlashMode.ON -> ImageCapture.FLASH_MODE_ON
    // 常亮由 CameraParamController 的 enableTorch 负责，拍照时按"开"处理
    FlashMode.TORCH -> ImageCapture.FLASH_MODE_ON
}
