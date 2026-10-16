package com.lemon.shotcoach.rules

import com.lemon.shotcoach.model.CompositionScore
import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.Hint
import com.lemon.shotcoach.model.NormalizedPoint
import com.lemon.shotcoach.model.RuleOfThirds
import kotlin.math.abs

/**
 * 内置规则集（FR-11）。所有规则均为纯函数，文案通过 [resolve] 注入（来自 strings.xml）。
 *
 * 优先级设计原则：可立刻纠正的、视觉上最刺眼的排在最前。
 * 见 docs/ALGORITHMS.md §9。
 *
 * ## v1.1：主体类规则改用 [FrameSignals.effectiveSubject]
 *
 * v1.0 用显著性质心当主体位置，而那个质心被中心先验强行拉向画面中央 ——
 * 主体明明在右侧三分点，规则却判"偏了"。v1.1 一律读 [FrameSignals.effectiveSubject]：
 * 端侧 AI 检测框中心优先，没有 AI 时才退回质心。**AI 关闭时行为与 v1.0 逐位一致。**
 */
object BuiltinRules {

    const val LEVEL_THRESHOLD_DEG = 3f
    const val EXPOSE_LOW = 60f
    const val EXPOSE_HIGH = 200f
    const val CLIP_RATIO = 0.15f
    const val BLUR_SCORE = 40
    const val SUBJECT_OFF_DIST = 0.15f
    const val FACE_SMALL_WIDTH = 0.12f
    const val GOOD_SCORE = 85

    /** 端侧 AI 美感分下限（NIMA 1..10）。低于此值提示"整体观感偏弱"。 */
    const val AESTHETIC_LOW = 5.0f

    fun all(resolve: (String) -> String): List<Rule> = listOf(
        ExposeLowRule(resolve),
        ExposeHighRule(resolve),
        LevelRule(resolve),
        BlurRule(resolve),
        SubjectOffRule(resolve),
        FaceSmallRule(resolve),
        AestheticLowRule(resolve),
        FaceNoneRule(resolve),
        GoodRule(resolve),
    )
}

// ── R-EXPOSE_LOW ─────────────────────────────────────────────
class ExposeLowRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_EXPOSE_LOW"
    override val priority = 95
    override val aspect = CompositionScore.Aspect.EXPOSURE
    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        val hit = s.brightness < BuiltinRules.EXPOSE_LOW || s.clipLowRatio > BuiltinRules.CLIP_RATIO
        return if (hit) Rule.Match("hint_expose_low", Hint.Direction.UP) else null
    }

    override fun textFor(m: Rule.Match) = resolve(m.textKey)
}

// ── R-EXPOSE_HIGH ────────────────────────────────────────────
class ExposeHighRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_EXPOSE_HIGH"
    override val priority = 95
    override val aspect = CompositionScore.Aspect.EXPOSURE
    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        val hit = s.brightness > BuiltinRules.EXPOSE_HIGH || s.clipHighRatio > BuiltinRules.CLIP_RATIO
        return if (hit) Rule.Match("hint_expose_high", Hint.Direction.DOWN) else null
    }

    override fun textFor(m: Rule.Match) = resolve(m.textKey)
}

// ── R-LEVEL ──────────────────────────────────────────────────
class LevelRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_LEVEL"
    override val priority = 90
    override val aspect = CompositionScore.Aspect.LEVEL
    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        val roll = s.effectiveRoll
        if (abs(roll) <= BuiltinRules.LEVEL_THRESHOLD_DEG) return null
        // roll > 0：设备顺时针倾斜 → 提示向左回转
        return if (roll > 0f) Rule.Match("hint_level_left", Hint.Direction.LEFT)
        else Rule.Match("hint_level_right", Hint.Direction.RIGHT)
    }

    override fun textFor(m: Rule.Match) = resolve(m.textKey)
}

// ── R-BLUR ───────────────────────────────────────────────────
class BlurRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_BLUR"
    override val priority = 80
    override val aspect = CompositionScore.Aspect.SHARPNESS
    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? =
        if (c.sharpnessScore < BuiltinRules.BLUR_SCORE) Rule.Match("hint_blur") else null

    override fun textFor(m: Rule.Match) = resolve(m.textKey)
}

// ── R-SUBJECT_OFF ────────────────────────────────────────────
class SubjectOffRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_SUBJECT_OFF"
    override val priority = 70
    override val aspect = CompositionScore.Aspect.SUBJECT

    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        // 主体来源：端侧 AI 检测框中心 > 显著性质心（见 FrameSignals.effectiveSubject）
        val subject = s.effectiveSubject ?: return null
        // "有没有主体"这条判据只对 CV 回退路径有意义；AI 检测框是模型给的，不用再怀疑
        if (!s.hasAiSubject && s.saliencyConcentration < 0.02f) return null

        // 找最近的三分点（或中心），比较偏差
        val target: NormalizedPoint = RuleOfThirds.POINTS.minByOrNull { subject.distanceTo(it) }
            ?: NormalizedPoint.CENTER
        val dx = target.x - subject.x
        val dy = target.y - subject.y

        if (maxOf(abs(dx), abs(dy)) < BuiltinRules.SUBJECT_OFF_DIST) return null

        // 取主导轴（偏差更大的那一维）
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0) Rule.Match("hint_subject_right", Hint.Direction.RIGHT)
            else Rule.Match("hint_subject_left", Hint.Direction.LEFT)
        } else {
            if (dy > 0) Rule.Match("hint_subject_down", Hint.Direction.DOWN)
            else Rule.Match("hint_subject_up", Hint.Direction.UP)
        }
    }

    override fun textFor(m: Rule.Match) = resolve(m.textKey)
}

// ── R-FACE_SMALL ─────────────────────────────────────────────
class FaceSmallRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_FACE_SMALL"
    override val priority = 60
    override val aspect = CompositionScore.Aspect.SUBJECT
    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        val face = s.primaryFace ?: return null
        return if (face.width < BuiltinRules.FACE_SMALL_WIDTH) Rule.Match("hint_face_small") else null
    }

    override fun textFor(m: Rule.Match) = resolve(m.textKey)
}

// ── R-AESTHETIC_LOW ──────────────────────────────────────────
/**
 * 端侧 AI 美感分偏低（NIMA < 5.0 / 10）。
 *
 * 这是全项目**唯一一条直接由神经网络输出驱动的建议** —— 其余规则都是人工阈值 if-else。
 * 它衡量的是"整体观感"，是曝光/水平/清晰度这些单点指标**都正常、但照片就是不好看**时的兜底。
 *
 * AI 美感通道未启用时 `aestheticScore == null` → 规则不触发（而不是永远触发）。
 * 优先级 50：低于失焦(80)/主体偏(70)，高于"没检测到人"(40)。
 */
class AestheticLowRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_AESTHETIC_LOW"
    override val priority = 50
    override val aspect = CompositionScore.Aspect.AESTHETIC

    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        val aesthetic = s.aestheticScore ?: return null
        return if (aesthetic < BuiltinRules.AESTHETIC_LOW) Rule.Match("hint_aesthetic_low") else null
    }

    override fun textFor(m: Rule.Match) = resolve(m.textKey)
}

// ── R-FACE_NONE ──────────────────────────────────────────────
class FaceNoneRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_FACE_NONE"
    override val priority = 40
    override val aspect = CompositionScore.Aspect.SUBJECT
    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        val noFace = s.faces.isEmpty()
        val noSubject = s.effectiveSubject == null ||
            (!s.hasAiSubject && s.saliencyConcentration < 0.02f)
        return if (noFace && noSubject) Rule.Match("hint_face_none") else null
    }

    override fun textFor(m: Rule.Match) = resolve(m.textKey)
}

// ── R-GOOD ───────────────────────────────────────────────────
class GoodRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_GOOD"
    override val priority = 10
    override val aspect: CompositionScore.Aspect? = null   // 与具体子项无关，总是参与评估
    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? =
        if (c.total > BuiltinRules.GOOD_SCORE) Rule.Match("hint_good") else null

    override fun textFor(m: Rule.Match) = resolve(m.textKey)
}
