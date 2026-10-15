package com.lemon.shotcoach.model

/**
 * 构图评分结果。四个子项分各 0..100，total 为加权总分。
 *
 * 权重依据见 docs/ALGORITHMS.md §7.5：
 * 构图(主体) 40% · 水平 25% · 曝光 20% · 清晰度 15%
 */
data class CompositionScore(
    val subjectScore: Int,
    val levelScore: Int,
    val exposureScore: Int,
    val sharpnessScore: Int,
    val total: Int,
    /** 加权扣分最大的子项 —— 规则引擎优先针对它给建议 */
    val worstAspect: Aspect?,
) {
    enum class Aspect { SUBJECT, LEVEL, EXPOSURE, SHARPNESS }

    companion object {
        const val W_SUBJECT = 0.40f
        const val W_LEVEL = 0.25f
        const val W_EXPOSURE = 0.20f
        const val W_SHARPNESS = 0.15f
    }
}
