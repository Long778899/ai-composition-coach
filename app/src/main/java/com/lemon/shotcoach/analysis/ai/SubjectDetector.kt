package com.lemon.shotcoach.analysis.ai

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * 主体检测（FR-5 的 AI 实现）。EfficientDet-Lite0（int8），COCO 80 类，端侧神经网络。
 *
 * ## 为什么要换掉原来的显著性
 *
 * 旧实现（[com.lemon.shotcoach.analysis.analyzer.SaliencyAnalyzer]）是"中心先验 + Sobel 梯度"，
 * 而且 `spectralResidual()` 那条注释写着频域残差、实际直接转调中心先验 —— σ = 0.35·min(w,h)
 * 的高斯权把质心**结构性地拉向画面中心**。主体真在右侧三分点时，会被判成"偏离构图"，
 * 于是给出错误的"把主体往左移"。
 *
 * 神经网络直接回归出主体框，位置是**测出来的**，不是加权猜出来的。
 *
 * ## 线程与生命周期
 *
 * MediaPipe Task API 不是线程安全的：本类必须由分析线程独占（ARCHITECTURE §2）。
 * 构造时探测模型是否存在，[available] 为 false 时 [detect] 恒返回空表，调用方回退 CV。
 */
class SubjectDetector(
    context: Context,
    private val delegate: Delegate = Delegate.CPU,
) : AutoCloseable {

    /** 归一化检测结果（0..1，相对输入图像） */
    data class Detection(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val label: String,
        val score: Float,
    ) {
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f

        /** 面积占比 0..1 —— 挑主体时按它排序（最大的那个通常就是主体） */
        val areaRatio: Float get() = ((right - left) * (bottom - top)).coerceIn(0f, 1f)

        /** 排序键：面积为主、置信度为辅 */
        val salience: Float get() = areaRatio * score
    }

    private val detector: ObjectDetector? = create(context, delegate)

    val available: Boolean get() = detector != null

    /**
     * @param image  由 [MpImageBridge] 构造、尺寸为 [width]x[height] 的图像
     * @return 归一化检测框；不可用或异常时为空表（绝不抛异常 —— 一帧失败不该打断取景）
     */
    fun detect(image: MPImage, width: Int, height: Int): List<Detection> {
        val d = detector ?: return emptyList()
        if (width <= 0 || height <= 0) return emptyList()
        return runCatching {
            d.detect(image).detections().mapNotNull { det ->
                val box = det.boundingBox() ?: return@mapNotNull null
                val cat = det.categories()?.firstOrNull()
                Detection(
                    left = (box.left / width).coerceIn(0f, 1f),
                    top = (box.top / height).coerceIn(0f, 1f),
                    right = (box.right / width).coerceIn(0f, 1f),
                    bottom = (box.bottom / height).coerceIn(0f, 1f),
                    label = cat?.categoryName().orEmpty(),
                    score = cat?.score() ?: 0f,
                )
            }
        }.getOrElse { emptyList() }
    }

    override fun close() {
        runCatching { detector?.close() }
    }

    companion object {
        /** 取前 5 个候选，由上层按面积挑主体 */
        const val MAX_RESULTS = 5

        /** 阈值偏松：宁可多给候选，也不要漏掉真正的主体 */
        const val SCORE_THRESHOLD = 0.35f

        private fun create(context: Context, delegate: Delegate): ObjectDetector? = try {
            if (!AiModels.has(context, AiModels.SUBJECT_DETECTOR)) {
                null
            } else {
                val base = BaseOptions.builder()
                    .setModelAssetPath(AiModels.SUBJECT_DETECTOR)
                    .setDelegate(delegate)
                    .build()
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(MAX_RESULTS)
                    .setScoreThreshold(SCORE_THRESHOLD)
                    .build()
                ObjectDetector.createFromOptions(context, options)
            }
        } catch (_: Throwable) {
            // 模型损坏 / ABI 不匹配 / native 库加载失败 —— 一律降级，不崩
            null
        }
    }
}
