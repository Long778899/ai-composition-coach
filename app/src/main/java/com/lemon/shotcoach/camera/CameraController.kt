package com.lemon.shotcoach.camera

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
 * 不含任何视觉算法。
 *
 * 关键点（见 docs/ARCHITECTURE.md §2）：
 *  - ImageAnalysis 使用**独立单线程 Executor**，与预览渲染分离；
 *  - `STRATEGY_KEEP_ONLY_LATEST` + [FrameThrottler] 双重防积压。
 */
class CameraController(
    private val context: Context,
    private val analyzer: FrameAnalyzer,
    private val orientation: OrientationAnalyzer,
    private val onState: (UiState) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shotcoach-analysis").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    private var cameraProvider: ProcessCameraProvider? = null

    /** 复用的 RGBA Mat（NFR-2） */
    private val rgbaMat = Mat()

    @SuppressLint("UnsafeOptInUsageError")
    fun bind(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

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

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (t: Throwable) {
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        try {
            // ImageProxy(YUV_420_888/RGBA) → Mat
            val src = proxyToMat(proxy)
            val state = analyzer.analyze(src, orientation.rollDegrees(), now)
            if (state != null) onState(state)
        } catch (t: Throwable) {
            onError(t)
        } finally {
            proxy.close()   // 必须在 finally 关闭，否则丢帧卡死
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

    fun unbind() {
        cameraProvider?.unbindAll()
    }

    fun shutdown() {
        unbind()
        analysisExecutor.shutdown()
    }

    /** 供测试：任意 Bitmap → Mat（RGBA） */
    fun bitmapToRgba(bitmap: android.graphics.Bitmap): Mat {
        val m = Mat()
        Utils.bitmapToMat(bitmap, m)
        if (m.channels() == 3) Imgproc.cvtColor(m, m, Imgproc.COLOR_RGB2RGBA)
        return m
    }
}
