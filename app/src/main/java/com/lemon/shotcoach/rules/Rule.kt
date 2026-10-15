package com.lemon.shotcoach.rules

import com.lemon.shotcoach.model.CompositionScore
import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.Hint

/**
 * 规则接口（US-07：新增规则不得修改分析层）。
 *
 * 规则是**纯函数**：给定信号与评分，返回 0 或 1 条提示，无副作用。
 * 文案通过 [textFor] 从资源提供，使规则本身保持无 Android 依赖、可单测。
 */
interface Rule {
    val id: String

    /** 优先级 0..100，越大越先展示 */
    val priority: Int

    /** 该规则关心的评分子项；null = 与评分无关，总是参与评估 */
    val aspect: CompositionScore.Aspect?

    /** 纯判断：命中则返回文案 key，否则 null */
    fun match(signals: FrameSignals, score: CompositionScore): Match?

    /** 由 UI/引擎注入的文案解析（来自 strings.xml） */
    fun textFor(match: Match): String

    data class Match(val textKey: String, val direction: Hint.Direction = Hint.Direction.NONE)

    /** 便捷构造：把 Match 变成 Hint */
    fun toHint(match: Match, ts: Long): Hint =
        Hint(id = id, text = textFor(match), direction = match.direction, priority = priority, shownAtMs = ts)
}
