package com.lemon.shotcoach.analysis

import com.lemon.shotcoach.model.CompositionScore
import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.NormalizedPoint
import com.lemon.shotcoach.model.RuleOfThirds
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 构图评分器（FR-9）。纯函数式，无状态、无 Android 依赖 → 可 JVM 单测。
 *
 * ## v1.1：引入"美感"子项（端侧神经网络）
 *
 * v1.0 的四路子项（主体/水平/曝光/清晰度）全部是**手调公式**：人能写出解析式，说明它不是 AI。
 * v1.1 起，当端侧 NIMA 模型可用时追加第五路 [CompositionScore.Aspect.AESTHETIC]，
 * 由神经网络直接给出 1..10 的美感分，权重 25%，传统四路按比例让出。
 *
 * 关键性质：**AI 不可用时（模型缺失/未启用）`aestheticScore == null`，
 * 权重回到 v1.0 原值。** 不存在"AI 半死不活导致评分漂移"的状态。
 * （v1.1.1 起这句话有个例外：**主体测不到**时该子项退赛并归一化 —— 见下方 v1.1.1 一节。）
 *
 * 主体分改用 [FrameSignals.effectiveSubject]：有 AI 检测框时用**测出来的**主体中心，
 * 否则退回显著性质心。见 docs/ALGORITHMS.md §7、§10。
 *
 * ## v1.1.1：主体子项可以**缺席**
 *
 * 以前"没测到主体"会被打 60 分，而这个 60 分恰好是四项里扣分最重的
 * （权重 40%，扣 (100−60)×0.40 = 16 分），于是 [CompositionScore.worstAspect] **恒为 SUBJECT**，
 * 规则引擎只放主体类规则进场；而主体三条规则里另外两条又都因为"没有坐标/没有人脸"返回 null，
 * 结果只剩一条"把主体放进画面里"，永远换不掉 —— 拍风景（没有 COCO 物体、没有人脸）时尤其明显。
 *
 * 现在：主体没有依据（[FrameSignals.subjectKnown] 为 false）时整项**退赛**，
 * 权重在其余子项之间重新归一化 —— **只对测到的东西打分**。
 * 参评子项集合与旧版完全相同时权重和恰为 1.0（跳过除法），输出与旧版逐位一致。
 */
object CompositionScorer {

    /** "适中"亮度中心 */
    private const val IDEAL_BRIGHTNESS = 130f

    /** 清晰度归一化参考方差：达到此值即满分 */
    private const val REFERENCE_SHARPNESS = 220f

    /**
     * 主体缺依据时的**展示用**兜底分。
     *
     * 只用于 HUD 显示；**不参与加权、不参与最差子项评选**（见 [score]）。
     * 名字保留是为了兼容 —— 以前它真的会计入总分，那正是 bug 的源头。
     */
    private const val NO_SUBJECT_SCORE = 60

    fun score(signals: FrameSignals): CompositionScore {
        val subject = subjectScore(signals)
        val level = levelScore(signals.effectiveRoll)
        val exposure = exposureScore(signals.brightness)
        val sharpness = sharpnessScore(signals.sharpness)
        val aesthetic = signals.aestheticScore?.let { CompositionScore.aestheticToScore(it) }

        // 两套权重：有 AI 美感分才启用第二套，否则逐位兼容 v1.0
        val ai = aesthetic != null
        val wSubject = if (ai) CompositionScore.W_SUBJECT_AI else CompositionScore.W_SUBJECT
        val wLevel = if (ai) CompositionScore.W_LEVEL_AI else CompositionScore.W_LEVEL
        val wExposure = if (ai) CompositionScore.W_EXPOSURE_AI else CompositionScore.W_EXPOSURE
        val wSharpness = if (ai) CompositionScore.W_SHARPNESS_AI else CompositionScore.W_SHARPNESS

        // 实际参赛的子项 = (子项, 分数, 权重)。
        // 主体**没测到**时整项退赛，而不是拿 60 分凑数。
        val parts = buildList {
            if (signals.subjectKnown) {
                add(Triple(CompositionScore.Aspect.SUBJECT, subject, wSubject))
            }
            add(Triple(CompositionScore.Aspect.LEVEL, level, wLevel))
            add(Triple(CompositionScore.Aspect.EXPOSURE, exposure, wExposure))
            add(Triple(CompositionScore.Aspect.SHARPNESS, sharpness, wSharpness))
            if (aesthetic != null) {
                add(Triple(CompositionScore.Aspect.AESTHETIC, aesthetic, CompositionScore.W_AESTHETIC_AI))
            }
        }

        // 权重和：参赛集合与旧版一致时恰为 1.0 → 不做除法，保证逐位兼容。
        // 有子项退赛时才归一化。
        val wSum = parts.fold(0f) { acc, p -> acc + p.third }
        var acc = 0f
        for (p in parts) acc += p.second * p.third
        val total = if (wSum >= 0.999f) {
            // 参赛集合与旧版一致 → 沿用 v1.0 的**截断**语义，逐位兼容
            acc.toInt()
        } else {
            // 有子项退赛 → 取加权**平均**。这里必须四舍五入：
            // 浮点除法会把 60.0f / 0.6000001f 算成 99.99998，截断后满分变 99 分。
            (acc / wSum).roundToInt()
        }.coerceIn(0, 100)

        // 加权扣分最大的子项 —— 只在**参赛**子项里选，所以主体未测到时它不可能是 SUBJECT
        val worst = parts.maxByOrNull { (100 - it.second) * it.third }?.first

        return CompositionScore(
            subjectScore = subject,
            levelScore = level,
            exposureScore = exposure,
            sharpnessScore = sharpness,
            total = total,
            worstAspect = worst,
            aestheticScore = aesthetic,
            subjectKnown = signals.subjectKnown,
        )
    }

    /**
     * 主体位置分：主体到"最近的合法构图点"的距离。
     * 合法构图点 = 4 个三分点 ∪ 画面中心（中心带 1.3 倍宽容系数）。
     *
     * 主体来源优先级：端侧 AI 检测框中心 > 显著性质心。
     * 两者都没有（[FrameSignals.subjectKnown] 为 false）时返回 [NO_SUBJECT_SCORE]，
     * 但该分**不会计入总分**，也不会成为最差子项 —— 见 [score]。
     */
    fun subjectScore(signals: FrameSignals): Int {
        if (!signals.subjectKnown) return NO_SUBJECT_SCORE
        val s = signals.effectiveSubject ?: return NO_SUBJECT_SCORE

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
