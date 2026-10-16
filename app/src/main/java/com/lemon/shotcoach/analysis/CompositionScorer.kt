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
 * ## v1.1 变化：引入"美感"子项（端侧神经网络）
 *
 * v1.0 的四路子项（主体/水平/曝光/清晰度）全部是**手调公式**：人能写出解析式，说明它不是 AI。
 * v1.1 起，当端侧 NIMA 模型可用时追加第五路 [CompositionScore.Aspect.AESTHETIC]，
 * 由神经网络直接给出 1..10 的美感分，权重 25%，传统四路按比例让出。
 *
 * 关键性质：**AI 不可用时（模型缺失/未启用）`aestheticScore == null`，
 * 权重回到 v1.0 原值，输出与 v1.0 逐位一致。** 不存在"AI 半死不活导致评分漂移"的状态。
 *
 * 主体分改用 [FrameSignals.effectiveSubject]：有 AI 检测框时用**测出来的**主体中心，
 * 否则退回显著性质心。见 docs/ALGORITHMS.md §7、§10。
 */
object CompositionScorer {

    /** "适中"亮度中心 */
    private const val IDEAL_BRIGHTNESS = 130f

    /** 清晰度归一化参考方差：达到此值即满分 */
    private const val REFERENCE_SHARPNESS = 220f

    /** 无主体时的兜底分（交由规则层给弱提示） */
    private const val NO_SUBJECT_SCORE = 60

    fun score(signals: FrameSignals): CompositionScore {
        val subject = subjectScore(signals)
        val level = levelScore(signals.effectiveRoll)
        val exposure = exposureScore(signals.brightness)
        val sharpness = sharpnessScore(signals.sharpness)
        val aesthetic = signals.aestheticScore?.let { CompositionScore.aestheticToScore(it) }

        // 两套权重：有 AI 美感分才启用第二套，否则逐位兼容 v1.0
        val total = if (aesthetic == null) {
            (subject * CompositionScore.W_SUBJECT +
                level * CompositionScore.W_LEVEL +
                exposure * CompositionScore.W_EXPOSURE +
                sharpness * CompositionScore.W_SHARPNESS).toInt().coerceIn(0, 100)
        } else {
            (subject * CompositionScore.W_SUBJECT_AI +
                level * CompositionScore.W_LEVEL_AI +
                exposure * CompositionScore.W_EXPOSURE_AI +
                sharpness * CompositionScore.W_SHARPNESS_AI +
                aesthetic * CompositionScore.W_AESTHETIC_AI).toInt().coerceIn(0, 100)
        }

        // 加权扣分最大的子项
        val weighted = buildList {
            if (aesthetic == null) {
                add(CompositionScore.Aspect.SUBJECT to (100 - subject) * CompositionScore.W_SUBJECT)
                add(CompositionScore.Aspect.LEVEL to (100 - level) * CompositionScore.W_LEVEL)
                add(CompositionScore.Aspect.EXPOSURE to (100 - exposure) * CompositionScore.W_EXPOSURE)
                add(CompositionScore.Aspect.SHARPNESS to (100 - sharpness) * CompositionScore.W_SHARPNESS)
            } else {
                add(CompositionScore.Aspect.SUBJECT to (100 - subject) * CompositionScore.W_SUBJECT_AI)
                add(CompositionScore.Aspect.LEVEL to (100 - level) * CompositionScore.W_LEVEL_AI)
                add(CompositionScore.Aspect.EXPOSURE to (100 - exposure) * CompositionScore.W_EXPOSURE_AI)
                add(CompositionScore.Aspect.SHARPNESS to (100 - sharpness) * CompositionScore.W_SHARPNESS_AI)
                add(CompositionScore.Aspect.AESTHETIC to (100 - aesthetic) * CompositionScore.W_AESTHETIC_AI)
            }
        }
        val worst = weighted.maxByOrNull { it.second }?.first

        return CompositionScore(
            subjectScore = subject,
            levelScore = level,
            exposureScore = exposure,
            sharpnessScore = sharpness,
            total = total,
            worstAspect = worst,
            aestheticScore = aesthetic,
        )
    }

    /**
     * 主体位置分：主体到"最近的合法构图点"的距离。
     * 合法构图点 = 4 个三分点 ∪ 画面中心（中心带 1.3 倍宽容系数）。
     *
     * 主体来源优先级：端侧 AI 检测框中心 > 显著性质心。
     * AI 通道**不做**显著性浓度门槛——框是模型测出来的，不存在"显著性太弱"的问题；
     * 只有 CV 回退路径才需要 `saliencyConcentration >= 0.02` 这个"有没有主体"的判据。
     */
    fun subjectScore(signals: FrameSignals): Int {
        val s = signals.effectiveSubject ?: return NO_SUBJECT_SCORE
        if (!signals.hasAiSubject && signals.saliencyConcentration < 0.02f) return NO_SUBJECT_SCORE

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
