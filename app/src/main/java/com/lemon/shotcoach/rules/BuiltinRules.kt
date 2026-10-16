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
 * （例外：主体测不到时主体子项退赛并归一化，见 v1.1.1。）
 *
 * ## v1.1.1：从"人像中心"改为"场景无关"
 *
 * 起因：拍风景时提示恒定停在"把主体放进画面里"，换不掉。三处修正：
 *
 *  1. **删掉 `R_FACE_NONE`，且不做替代。** 它要求画面里有人脸 —— 风景照永远不满足，
 *     等于把景物永久判为"缺主体"。而且"没测到主体"对风景**不是缺陷**
 *     （风光、建筑、静物都可以没有"主体"），在给不出可执行建议时，正确答案是安静。
 *  2. **新增 [HorizonPlacementRule]** —— 拍景物真正有用的那条建议（地平线压三分线）。
 *     v1.0 算了地平线倾角却从没用过它的**高度**，拍风景等于一条建议都没有。
 *  3. **主体判据统一收敛到 [FrameSignals.subjectKnown]**，与评分器共用同一定义，
 *     不再各写各的 `0.02` 魔数。
 *
 * ## v1.1.2：水平从"抢占提示位"降为"兜底提示"
 *
 * 起因：用户报"水平的检测过于严格，构图建议几乎没有"。
 *
 * 实测（0~10° 每 0.5° 一档、共 21 档）：`R_LEVEL` 占掉 **14 档**，构图建议只剩 7 档。
 * 机理在优先级阶梯 —— 原 `R_LEVEL` = 90，而 [com.lemon.shotcoach.rules.RuleEngine] 的
 * `filter { aspect == null || aspect == worstAspect || priority >= 90 }`
 * 使 `priority >= 90` 的规则**无条件参赛**，于是水平(90) 永远压过主体(70)、地平线(65)。
 * 再叠加 3° 的阈值（手持自然晃动就在 ±3° 上下），提示位就被它垄断了。
 *
 * 三处修正：
 *  1. 阈值 3° → [LEVEL_THRESHOLD_DEG]（5°）：手持常态不再算"倾斜"。
 *  2. [LevelRule] 优先级 90 → **55**：只在水平确为**最差子项**时才参赛，
 *     构图类规则（地平线 65 / 主体 70 / 人脸 60）一律优先。
 *     依据是倾斜**已经有实时水平基准线兜着**（OverlayView，|roll| < 1° 变绿），
 *     不必再占用唯一的一行提示位。
 *  3. 新增 [LevelSevereRule]（priority 92，> 10°）：真歪得厉害时仍然强制播报。
 */
object BuiltinRules {

    /**
     * 轻微倾斜的告警阈值（度）。
     *
     * v1.1.2 从 3° 放宽到 5°：手持拍摄的自然晃动就在 ±3° 上下，3° 会让 [LevelRule]
     * 几乎每帧命中，把构图建议全挤掉（实测见上方 v1.1.2 说明）。
     */
    const val LEVEL_THRESHOLD_DEG = 5f

    /**
     * 严重倾斜阈值（度）。超过它照片基本就废了，[LevelSevereRule] 会**压过构图建议**强制播报。
     *
     * 取 10° 而非更小：此时水平分（每度扣 12 分）已经扣到 0，分数与提示说的是同一件事。
     */
    const val LEVEL_SEVERE_DEG = 10f
    const val EXPOSE_LOW = 60f
    const val EXPOSE_HIGH = 200f
    const val CLIP_RATIO = 0.15f
    const val BLUR_SCORE = 40
    const val SUBJECT_OFF_DIST = 0.15f
    const val FACE_SMALL_WIDTH = 0.12f
    const val GOOD_SCORE = 85

    /** 端侧 AI 美感分下限（NIMA 1..10）。低于此值提示"整体观感偏弱"。 */
    const val AESTHETIC_LOW = 5.0f

    /** 地平线偏离最近三分线的容忍量（归一化高度）。超过它才提示。 */
    const val HORIZON_OFF_DIST = 0.10f

    /** 三分法的上/下三分线（归一化高度，0 = 画面顶） */
    const val HORIZON_UPPER_THIRD = 1f / 3f
    const val HORIZON_LOWER_THIRD = 2f / 3f

    fun all(resolve: (String) -> String): List<Rule> = listOf(
        ExposeLowRule(resolve),
        ExposeHighRule(resolve),
        LevelRule(resolve),
        LevelSevereRule(resolve),
        BlurRule(resolve),
        SubjectOffRule(resolve),
        HorizonPlacementRule(resolve),
        FaceSmallRule(resolve),
        AestheticLowRule(resolve),
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
/**
 * 轻微倾斜（v1.1.2 **降级**：priority 90 → 55）。
 *
 * 原来 `priority = 90` 让它**无条件参赛**，只要歪过 3° 就压过地平线建议(65)与主体建议(70)
 * —— 这就是"构图建议几乎没有"的成因。降到 55 后它只在水平确为最差子项时才说话，
 * 构图类规则一律优先。
 *
 * 敢降的理由：倾斜有专属的**实时水平基准线**（见 OverlayView，|roll| < 1° 变绿），
 * 用户随时看得到；一行提示位是稀缺资源，该留给构图（本 App 的用途）。
 * 真歪得厉害时由 [LevelSevereRule] 强制播报。
 */
class LevelRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_LEVEL"
    override val priority = 55
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

// ── R-LEVEL_SEVERE ───────────────────────────────────────────
/**
 * 严重倾斜（v1.1.2 新增，priority 92）。
 *
 * 与 [LevelRule] 的分工是一条**能一句话讲清的分界线**：
 *  - ≤ [BuiltinRules.LEVEL_THRESHOLD_DEG]（5°）→ 交给实时水平线，提示不吭声；
 *  - 5° ~ [BuiltinRules.LEVEL_SEVERE_DEG]（10°）→ 提示位让给构图建议，倾斜由水平线兜着；
 *  - \> 10° → 照片已经废了，**压过构图建议**强制播报。
 *
 * `priority = 92 >= 90` 使它无条件参赛（正是我们想要的"压过一切"），
 * 但排在曝光(95)之后 —— 曝光错误更致命。
 */
class LevelSevereRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_LEVEL_SEVERE"
    override val priority = 92
    override val aspect = CompositionScore.Aspect.LEVEL

    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        val roll = s.effectiveRoll
        if (abs(roll) <= BuiltinRules.LEVEL_SEVERE_DEG) return null
        // 文案统一是"先回正"：歪到这个程度，关键是让用户知道这是首要问题
        return if (roll > 0f) Rule.Match("hint_level_severe", Hint.Direction.LEFT)
        else Rule.Match("hint_level_severe", Hint.Direction.RIGHT)
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
        // 主体来源：端侧 AI 检测框中心 > 显著性质心（见 FrameSignals.effectiveSubject）。
        // "有没有主体"的判据与评分器共用同一个定义（AI 框，或集中度够高的质心），
        // 不再各写各的 0.02 魔数。
        if (!s.subjectKnown) return null
        val subject = s.effectiveSubject ?: return null

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
 * 优先级 50：低于失焦(80)/主体偏(70)/地平线(65)，高于"构图不错"(10)。
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

// ── R-HORIZON ────────────────────────────────────────────────
/**
 * 地平线位置 —— **拍景物唯一真正有用的那条构图建议**（v1.1.1 新增）。
 *
 * 三分法对风景的第一条：地平线要么压在**上三分线**（1/3，强调地面/前景），
 * 要么压在**下三分线**（2/3，强调天空）。压在正中间是最典型的"平"构图。
 *
 * v1.0 只算了地平线的**倾角**（拿去做校平），从没用过它的**高度** ——
 * 于是拍风景时一条有效建议都给不出来。
 *
 * [FrameSignals.horizonY] 为 null（没检出可信地平线）时**静默**：不猜、不提示。
 * 这也是它与被删掉的 `R_FACE_NONE` 最大的区别 —— 宁可不说，不说错话。
 *
 * 优先级 65（低于主体偏 70、高于人脸小 60）。`aspect = null`：地平线是贯穿画面的
 * 元素，不该等到"某一个子项最差"才被允许说话。
 */
class HorizonPlacementRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_HORIZON"
    override val priority = 65
    override val aspect: CompositionScore.Aspect? = null

    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        val y = s.horizonY ?: return null
        val upper = BuiltinRules.HORIZON_UPPER_THIRD
        val lower = BuiltinRules.HORIZON_LOWER_THIRD
        val dUpper = abs(y - upper)
        val dLower = abs(y - lower)

        // 已经压在任一条三分线上（含容差）→ 这条建议没得说
        if (minOf(dUpper, dLower) <= BuiltinRules.HORIZON_OFF_DIST) return null

        return when {
            // 地平线太高（贴着画面顶部）→ 往下压，给天空留出空间
            y < upper -> Rule.Match("hint_horizon_down", Hint.Direction.DOWN)
            // 地平线太低（贴着画面底部）→ 往上抬，给前景留出空间
            y > lower -> Rule.Match("hint_horizon_up", Hint.Direction.UP)
            // 卡在正中间（最典型的"平"构图）→ 朝更近的那条三分线挪
            dUpper < dLower -> Rule.Match("hint_horizon_up", Hint.Direction.UP)
            else -> Rule.Match("hint_horizon_down", Hint.Direction.DOWN)
        }
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
