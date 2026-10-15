package com.lemon.shotcoach.rules

import com.lemon.shotcoach.model.CompositionScore
import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.Hint

/**
 * 规则引擎（FR-11）。纯 Kotlin，可 JVM 单测。
 *
 * 行为：
 *  1. 所有规则并行求值（纯函数）；
 *  2. 按 priority 降序取**第一条命中**的规则 → 保证只展示一条提示（US-06）；
 *  3. 驻留约束：同一条提示至少显示 [dwellMs] 才允许被其它提示替换（G3 防抖）。
 */
class RuleEngine(
    private val rules: List<Rule>,
    private val dwellMs: Long = 800L,
) {
    private var current: Hint? = null

    /** 上一次提示的 ID（用于驻留判断） */
    var currentId: String? = null
        private set

    /**
     * @return 本次应展示的提示；null 表示无提示
     */
    fun best(signals: FrameSignals, score: CompositionScore, nowMs: Long): Hint? {
        val candidates = rules
            .asSequence()
            // 快路径：若规则绑定到具体子项且该子项不是最差项，可跳过（见 docs/ARCHITECTURE.md §7.3 早退优化）
            .filter { it.aspect == null || it.aspect == score.worstAspect || it.priority >= 90 }
            .mapNotNull { rule ->
                rule.match(signals, score)?.let { rule.toHint(it, nowMs) }
            }
            .toList()

        val best = candidates.maxByOrNull { it.priority }

        // 驻留约束
        val prev = current
        if (best == null) {
            // 无候选：允许清空（但保留 prev 直到下一次有候选，避免闪烁成空白）
            current = null
            return null
        }
        if (prev != null && prev.id != best.id && nowMs - prev.shownAtMs < dwellMs) {
            return prev
        }
        // 同一条提示：沿用原 shownAtMs，使驻留计时连续
        val resolved = if (prev != null && prev.id == best.id) best.copy(shownAtMs = prev.shownAtMs) else best
        current = resolved
        currentId = resolved.id
        return resolved
    }

    fun reset() {
        current = null
        currentId = null
    }
}
