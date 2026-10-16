package com.lemon.shotcoach.analysis.ai

import android.content.Context
import com.lemon.shotcoach.model.NormalizedRect
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 端侧 AI 推理管线（路径 1）。三个真神经网络通道的唯一入口，也是唯一的**失败收口点**。
 *
 * ## 责任边界
 *
 * ```
 *   FrameAnalyzer ──► AiPipeline.analyze(rgba, want…) ──► AiResult
 *                        │
 *                        ├─ SubjectDetector   (EfficientDet-Lite0)  主体框 + 类别
 *                        ├─ FaceDetectorAi    (BlazeFace)           人脸框
 *                        └─ AestheticScorer   (NIMA MobileNet)      美感分 1..10
 * ```
 *
 * `FrameAnalyzer` 只知道"要哪些通道、拿到了什么"，完全不知道 MediaPipe / TFLite 的存在。
 * 这样替换模型、换 delegate（CPU→GPU/NNAPI）都不触碰分析层。
 *
 * ## 坐标约定（重要）
 *
 * 这里把**整帧**等比缩放到长边 320 后送进检测器 —— 不 letterbox、不裁切。
 * 于是检测框除以缩放后尺寸，**直接就是全帧归一化坐标**，不需要 `DownScaler.toNormalized()`
 * 那套黑边映射。少一次映射就少一类 off-by-one 错误。
 *
 * ## 降级
 *
 * 任一模型缺失 → 对应通道 `available = false`，`FrameAnalyzer` 自动回退传统 CV。
 * 三个都缺 → [anyAvailable] = false，[analyze] 直接返回空结果，管线等价于"没有 AI"。
 *
 * 非线程安全：由分析线程独占。
 */
class AiPipeline(
    context: Context,
    /** 关掉人脸通道（例如设置里禁用人脸提示）时传 false，省一份推理 */
    private val faceEnabled: Boolean = true,
) : AutoCloseable {

    /** 一帧的 AI 产出。全部为空 = 本帧没有 AI 参与 */
    data class AiResult(
        val detections: List<SubjectDetector.Detection> = emptyList(),
        val faces: List<NormalizedRect> = emptyList(),
        val aestheticScore: Float? = null,
    ) {
        val isEmpty: Boolean
            get() = detections.isEmpty() && faces.isEmpty() && aestheticScore == null
    }

    private val subjectDetector = SubjectDetector(context)
    private val faceDetector = FaceDetectorAi(context)
    private val aestheticScorer = AestheticScorer(context, AestheticScorer.Which.AESTHETIC)

    private val bridge = MpImageBridge()

    /** 检测用画布（等比缩放，长边 [DETECT_LONG_SIDE]），连续 Mat，跨帧复用 */
    private val canvas = Mat()

    val subjectAvailable: Boolean get() = subjectDetector.available
    val faceAvailable: Boolean get() = faceEnabled && faceDetector.available
    val aestheticAvailable: Boolean get() = aestheticScorer.available

    /** 有任何一路 AI 可用 */
    val anyAvailable: Boolean get() = subjectAvailable || faceAvailable || aestheticAvailable

    /** 人类可读的通道清单，用于 UI 角标与诊断日志 */
    val availableChannels: List<String>
        get() = buildList {
            if (subjectAvailable) add("主体")
            if (faceAvailable) add("人脸")
            if (aestheticAvailable) add("美感")
        }

    /**
     * @param rgba 整帧 RGBA（`DownScaler` 之前、或 `Output.content`，都行 —— 只要求是画面内容）
     */
    fun analyze(
        rgba: Mat,
        wantSubject: Boolean,
        wantFace: Boolean,
        wantAesthetic: Boolean,
    ): AiResult {
        if (!anyAvailable || rgba.empty() || rgba.type() != CvType.CV_8UC4) return AiResult()

        var detections: List<SubjectDetector.Detection> = emptyList()
        var faces: List<NormalizedRect> = emptyList()
        var aesthetic: Float? = null

        // ── 两个检测器共用一张 MPImage（一次缩放、一次打包）──
        if ((wantSubject && subjectAvailable) || (wantFace && faceAvailable)) {
            val scaled = scaleForDetection(rgba)
            if (scaled != null) {
                val w = scaled.cols()
                val h = scaled.rows()
                val image = bridge.build(scaled)
                try {
                    if (wantSubject && subjectAvailable) {
                        detections = subjectDetector.detect(image, w, h)
                    }
                    if (wantFace && faceAvailable) {
                        faces = faceDetector.detect(image, w, h)
                    }
                } finally {
                    // ByteBufferImageContainer.close() 是空实现（javap 已核实），
                    // 所以关掉 MPImage 不会碰我们复用的 canvas 缓冲区。
                    runCatching { image.close() }
                }
            }
        }

        if (wantAesthetic && aestheticAvailable) {
            aesthetic = aestheticScorer.score(rgba)?.score
        }

        return AiResult(detections = detections, faces = faces, aestheticScore = aesthetic)
    }

    /** 等比缩放到长边 [DETECT_LONG_SIDE]，输出连续 Mat（结果缓存在 [canvas]） */
    private fun scaleForDetection(rgba: Mat): Mat? {
        val w = rgba.cols()
        val h = rgba.rows()
        if (w <= 0 || h <= 0) return null
        val ratio = DETECT_LONG_SIDE.toFloat() / maxOf(w, h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return try {
            Imgproc.resize(
                rgba, canvas,
                Size(nw.toDouble(), nh.toDouble()),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            canvas
        } catch (_: Throwable) {
            null
        }
    }

    override fun close() {
        runCatching { subjectDetector.close() }
        runCatching { faceDetector.close() }
        runCatching { aestheticScorer.close() }
        runCatching { canvas.release() }
        bridge.release()
    }

    companion object {
        /**
         * 检测画布长边。320 是 EfficientDet-Lite0 的原生输入边长，
         * 也是"够小够快"与"人脸的像素够多"之间的折中（见 docs/ALGORITHMS.md §10.4）。
         */
        const val DETECT_LONG_SIDE = 320
    }
}
