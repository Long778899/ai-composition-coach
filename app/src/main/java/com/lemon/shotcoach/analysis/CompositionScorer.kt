package com.lemon.shotcoach.analysis

import com.lemon.shotcoach.model.CompositionScore
import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.NormalizedPoint
import com.lemon.shotcoach.model.RuleOfThirds
import kotlin.math.abs
import kotlin.math.min

/**
 * 构图评分器（FR-9）。纯函数式，无状态、无 Android 依赖 → 可 JVM 单测。
 *
 * 子项与权重见 docs/ALGORITHMS.md §7。
 */
object CompositionScorer {

    /** "适中"亮度中心 */
    private const val IDEAL_BRIGHTNESS = 130f

    /** 清晰度归一化参考方差：达到此值即满分 */
    private const val REFERENCE_SHARPNESS = 220f

    fun score(signals: FrameSignals): CompositionScore {
        val subject = subjectScore(signals)
        val level = levelScore(signals.effectiveRoll)
        val exposure = exposureScore(signals.brightness)
        val sharpness = sharpnessScore(signals.sharpness)

        val total = (subject * CompositionScore.W_SUBJECT +
            level * CompositionScore.W_LEVEL +
            exposure * CompositionScore.W_EXPOSURE +
            sharpness * CompositionScore.W_SHARPNESS).toInt().coerceIn(0, 100)

        // 加权扣分最大的子项
        val weighted = listOf(
            CompositionScore.Aspect.SUBJECT to (100 - subject) * CompositionScore.W_SUBJECT,
            CompositionScore.Aspect.LEVEL to (100 - level) * CompositionScore.W_LEVEL,
            CompositionScore.Aspect.EXPOSURE to (100 - exposure) * CompositionScore.W_EXPOSURE,
            CompositionScore.Aspect.SHARPNESS to (100 - sharpness) * CompositionScore.W_SHARPNESS,
        )
        val worst = weighted.maxByOrNull { it.second }?.first

        return CompositionScore(
            subjectScore = subject,
            levelScore = level,
            exposureScore = exposure,
            sharpnessScore = sharpness,
            total = total,
            worstAspect = worst,
        )
    }

    /**
     * 主体位置分：主体到"最近的合法构图点"的距离。
     * 合法构图点 = 4 个三分点 ∪ 画面中心（中心带 1.3 倍宽容系数）。
     */
    fun subjectScore(signals: FrameSignals): Int {
        val s = signals.subject ?: return 60 // 无明确主体：给中间分，交由规则层给弱提示
        if (signals.saliencyConcentration < 0.02f) return 60

        val dThirds = RuleOfThirds.POINTS.minOf { s.distanceTo(it) }
        val dCenter = s.distanceTo(NormalizedPoint.CENTER) * 1.3f
        val d = min(dThirds, dCenter)

        return (100f - d * 400f).toInt().coerceIn(0, 100)
    }

    /** 水平分：|roll| 每度扣 12 分 */
    fun levelScore(rollDegrees: Float): Int =
        (100f - abs(rollDegrees) * 12f).toInt().coerceIn(0, 100)

    /** 曝光分：偏离"适中亮度"每 1 级扣 1.4 分 */
    fun exposureScore(brightness: Float): Int =
        (100f - abs(brightness - IDEAL_BRIGHTNESS) * 1.4f).toInt().coerceIn(0, 100)

    /** 清晰度分：Laplacian 方差映射到 0..100，sqrt 曲线让暗部变化更可感 */
    fun sharpnessScore(variance: Float): Int {
        if (variance <= 0f) return 0
        val normalized = kotlin.math.sqrt(variance / REFERENCE_SHARPNESS) * 100f
        return normalized.toInt().coerceIn(0, 100)
    }
}
