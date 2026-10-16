package com.lemon.shotcoach.model

/**
 * 构图评分结果。各子项分 0..100，total 为加权总分。
 *
 * 权重依据见 docs/ALGORITHMS.md §7.5。
 *
 * ## 两套权重
 *
 * - **无 AI（与 v1.0 逐位一致）**：主体 40% · 水平 25% · 曝光 20% · 清晰度 15%
 * - **有 AI（v1.1 新增）**：主体 30% · 水平 18% · 曝光 15% · 清晰度 12% · **美感 25%**
 *
 * 美感分来自端侧 NIMA 神经网络，是全项目**唯一由模型直接给分**的子项。
 * 只有它存在时才启用第二套权重 —— 传统 CV 四路按比例让出 25% 交给神经网络。
 * AI 未启用时 `aestheticScore == null`，走第一套权重，行为与 v1.0 完全一致。
 */
data class CompositionScore(
    val subjectScore: Int,
    val levelScore: Int,
    val exposureScore: Int,
    val sharpnessScore: Int,
    val total: Int,
    /** 加权扣分最大的子项 —— 规则引擎优先针对它给建议 */
    val worstAspect: Aspect?,
    /** 端侧 AI 美感分 0..100（由 NIMA 1..10 映射）。null = 未启用 AI 美感通道 */
    val aestheticScore: Int? = null,
) {
    enum class Aspect { SUBJECT, LEVEL, EXPOSURE, SHARPNESS, AESTHETIC }

    companion object {
        // ── 无 AI：v1.0 原始权重，一字未改 ──
        const val W_SUBJECT = 0.40f
        const val W_LEVEL = 0.25f
        const val W_EXPOSURE = 0.20f
        const val W_SHARPNESS = 0.15f

        // ── 有 AI：传统四路让出 25% 给 NIMA 美感分 ──
        const val W_SUBJECT_AI = 0.30f
        const val W_LEVEL_AI = 0.18f
        const val W_EXPOSURE_AI = 0.15f
        const val W_SHARPNESS_AI = 0.12f
        const val W_AESTHETIC_AI = 0.25f

        /** NIMA 输出区间（AVA 数据集的 1..10 分档） */
        const val AESTHETIC_MIN = 1f
        const val AESTHETIC_MAX = 10f

        /** NIMA 原始分（1..10）→ 子项分（0..100），线性映射 */
        fun aestheticToScore(score10: Float): Int {
            val t = (score10 - AESTHETIC_MIN) / (AESTHETIC_MAX - AESTHETIC_MIN)
            return (t * 100f).toInt().coerceIn(0, 100)
        }
    }
}
