package com.lemon.shotcoach.model

/**
 * 面向用户的一条构图提示。
 *
 * 关键设计：这条提示的文案是**预先写好的模板**，不是运行时生成的自然语言。
 * "建议"= 阈值判断 + 模板命中，因此零推理成本、零延迟。
 * 见 docs/ALGORITHMS.md §9。
 */
data class Hint(
    /** 规则 ID，如 "R_LEVEL" */
    val id: String,
    /** 已本地化的文案（由规则的文案提供者给出） */
    val text: String,
    /** 视觉引导箭头方向 */
    val direction: Direction = Direction.NONE,
    /** 优先级 0..100，越大越优先展示 */
    val priority: Int = 0,
    /** 首次展示时间戳，用于驻留约束（FR-10） */
    val shownAtMs: Long = 0L,
) {
    enum class Direction { LEFT, RIGHT, UP, DOWN, NONE }

    /** 生成一条已打上展示时间的副本（引擎内部使用） */
    fun shownAt(ts: Long): Hint = copy(shownAtMs = ts)
}
