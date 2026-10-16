package com.lemon.shotcoach

import com.lemon.shotcoach.analysis.CompositionScorer
import com.lemon.shotcoach.model.CompositionScore
import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.Hint
import com.lemon.shotcoach.model.NormalizedPoint
import com.lemon.shotcoach.model.NormalizedRect
import com.lemon.shotcoach.rules.BuiltinRules
import com.lemon.shotcoach.rules.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 景物构图分析回归（v1.1.1）。
 *
 * ## 起因
 *
 * 用户报："拍景物时提示恒定停在'把主体放进画面里'，换不掉。"
 *
 * 用真代码复现后定位到**三个**根因，每个都在这里锁死：
 *
 *  1. **"没测到主体"被打成 60 分。** 扣分 (100-60)*0.40 = 16 是四项里最大的 →
 *     `worstAspect` 恒为 SUBJECT → 规则引擎只放主体类规则进场。
 *     现在主体无依据时整项**退赛**，权重在其余子项间归一化。
 *  2. **`R_FACE_NONE` 要求画面里有人脸** —— 风景照永远不满足，等于永久报错。
 *     已删除且不做替代："没测到主体"对风景不是缺陷，给不出建议时应该安静。
 *  3. **地平线的"高度"算出来没人用** → 拍景物一条有效建议都没有。
 *     新增 `R_HORIZON`，并给 HorizonAnalyzer 加了位置输出。
 *
 * 纯 JVM，不碰 Android / OpenCV / MediaPipe，可直接 `./gradlew test`。
 */
class LandscapeCompositionTest {

    /** 假文案解析器（真文案在 strings.xml，单测不引入 Android 资源） */
    private val texts = mapOf(
        "hint_expose_low" to "画面偏暗，增加曝光",
        "hint_expose_high" to "画面偏亮，降低曝光",
        "hint_level_left" to "相机向左回转一点",
        "hint_level_right" to "相机向右回转一点",
        "hint_blur" to "可能失焦，稳住手机",
        "hint_subject_left" to "把主体往左移一点",
        "hint_subject_right" to "把主体往右移一点",
        "hint_subject_up" to "把主体往上移一点",
        "hint_subject_down" to "把主体往下移一点",
        "hint_face_small" to "靠近一点",
        "hint_aesthetic_low" to "试试换个角度或简化背景",
        "hint_horizon_up" to "地平线往上抬一点",
        "hint_horizon_down" to "地平线往下压一点",
        "hint_good" to "构图不错，可以按了",
    )

    private fun resolve(key: String) = texts[key] ?: key

    /** 每次都新建引擎：避免上一帧的 `dwell` 状态影响断言 */
    private fun hint(s: FrameSignals): Hint? {
        val score = CompositionScorer.score(s)
        return RuleEngine(BuiltinRules.all(::resolve), dwellMs = 0L).best(s, score, 0L)
    }

    /**
     * 一张典型的干净风景：曝光、水平、清晰度都很好，但画面里**没有可识别的主体**。
     * `saliencyConcentration` 低于可信门槛 → 按定义"没测到主体"。
     */
    private fun landscape(horizonY: Float? = null, aesthetic: Float? = null) = FrameSignals(
        brightness = 132f,
        sharpness = 300f,
        rollDegrees = 0f,
        horizonDegrees = 0f,
        horizonY = horizonY,
        saliencyConcentration = 0.01f,
        aestheticScore = aesthetic,
    )

    // ── 根因 1：没测到 ≠ 测得差 ──────────────────────────────────

    @Test
    fun `景物没测到主体时 主体子项不参与评分`() {
        val s = landscape()
        assertFalse("景物照不该被判定为'知道主体在哪'", s.subjectKnown)

        val c = CompositionScorer.score(s)
        assertFalse(c.subjectKnown)
        assertNotEquals(
            "主体未测到时不该成为最差子项 —— 这正是死循环的成因",
            CompositionScore.Aspect.SUBJECT,
            c.worstAspect,
        )
    }

    @Test
    fun `主体退赛后 权重在其余子项间归一化`() {
        val c = CompositionScorer.score(landscape())
        // 水平 100 + 曝光 97 + 清晰 100，归一化后应是高分，
        // 而不是被"兜底 60 分的主体"拖到 84
        assertTrue("归一化后应为高分，实际 ${c.total}", c.total >= 95)
    }

    @Test
    fun `一帧里主体项的展示分仍然保留`() {
        // 展示分（HUD 用）不参与加权，但仍要有个值，UI 靠 subjectKnown 决定打"—"
        assertEquals(60, CompositionScorer.score(landscape()).subjectScore)
    }

    @Test
    fun `有主体的场景完全不受影响`() {
        val s = FrameSignals(
            brightness = 130f,
            sharpness = 220f,
            rollDegrees = 0f,
            subject = NormalizedPoint(1f / 3f, 1f / 3f),
            saliencyConcentration = 0.08f,
        )
        assertTrue(s.subjectKnown)

        val c = CompositionScorer.score(s)
        assertTrue(c.subjectKnown)
        assertEquals("主体正好在三分点应满分", 100, c.subjectScore)
        assertEquals("四项全满、无美感分 → 权重和仍为 1.0，与 v1.0 逐位一致", 100, c.total)
    }

    @Test
    fun `AI 主体框在显著性极弱时也算有依据`() {
        val s = FrameSignals(
            brightness = 130f,
            sharpness = 220f,
            subjectBox = NormalizedRect(0.28f, 0.28f, 0.40f, 0.40f),
            saliencyConcentration = 0f,
        )
        assertTrue("模型给的主体框不需要再经过显著性门槛", s.subjectKnown)
        assertTrue(CompositionScorer.score(s).subjectScore >= 95)
    }

    @Test
    fun `主体仍有依据时会正常提示偏离`() {
        // 主体落在画面正中偏下、离最近三分点足够远 → R_SUBJECT_OFF 应照常触发
        val s = FrameSignals(
            brightness = 130f,
            sharpness = 260f,
            rollDegrees = 0f,
            subjectBox = NormalizedRect(0.45f, 0.80f, 0.55f, 0.95f),
        )
        assertEquals("R_SUBJECT_OFF", hint(s)?.id)
    }

    // ── 根因 2：不再要求"有人脸" ────────────────────────────────

    @Test
    fun `规则集里已经没有 R_FACE_NONE`() {
        assertTrue(
            "R_FACE_NONE 是「人像中心」设计，会把风景照永久判为缺主体",
            BuiltinRules.all(::resolve).none { it.id == "R_FACE_NONE" },
        )
    }

    @Test
    fun `干净的风景给出正面反馈 而不是抱怨缺主体`() {
        assertEquals("R_GOOD", hint(landscape())?.id)
    }

    // ── 根因 3：地平线高度终于有人用 ───────────────────────────

    @Test
    fun `地平线压在三分线上时不提示`() {
        assertNull("压在 1/3 上不该说话", hint(landscape(horizonY = 1f / 3f))?.let { it.id.takeIf { id -> id == "R_HORIZON" } })
        assertNull("压在 2/3 上不该说话", hint(landscape(horizonY = 2f / 3f))?.let { it.id.takeIf { id -> id == "R_HORIZON" } })
    }

    @Test
    fun `地平线压正中间时提示调整`() {
        assertEquals("正中是最典型的「平」构图", "R_HORIZON", hint(landscape(horizonY = 0.5f))?.id)
    }

    @Test
    fun `地平线偏高提示往下压 偏低提示往上抬`() {
        val high = hint(landscape(horizonY = 0.12f))
        assertEquals("R_HORIZON", high?.id)
        assertEquals(Hint.Direction.DOWN, high?.direction)

        val low = hint(landscape(horizonY = 0.88f))
        assertEquals("R_HORIZON", low?.id)
        assertEquals(Hint.Direction.UP, low?.direction)
    }

    @Test
    fun `没有可信地平线时保持安静`() {
        assertNull(
            "horizonY 为 null 时不该猜",
            hint(landscape(horizonY = null))?.let { it.id.takeIf { id -> id == "R_HORIZON" } },
        )
    }

    @Test
    fun `地平线建议不会被"构图不错"盖掉`() {
        // 风光照其余指标都很好（total >= 85，R_GOOD 会命中），
        // 但地平线压正中是明确的构图问题 → 优先级 65 必须压过 R_GOOD 的 10
        val s = landscape(horizonY = 0.5f)
        assertTrue("前提：这张图总分确实够高", CompositionScorer.score(s).total > BuiltinRules.GOOD_SCORE)
        assertEquals("R_HORIZON", hint(s)?.id)
    }

    // ── 根因 4（v1.1.2）：水平抢占了唯一的一行提示位 ────────────
    //
    // 用户报"水平的检测过于严格，构图建议几乎没有"。实测（0~10° 共 21 档）
    // R_LEVEL 占掉 14 档。机理是 `priority >= 90` 让它无条件参赛，
    // 于是 90 的水平永远压过 70 的主体、65 的地平线。

    /** 倾斜若干度、其余正常的风景（地平线压在正中，本该给地平线建议） */
    private fun tiltedLandscape(roll: Float) = FrameSignals(
        brightness = 132f, sharpness = 300f,
        rollDegrees = roll, horizonDegrees = roll, horizonY = 0.5f,
        saliencyConcentration = 0.01f,
    )

    @Test
    fun `轻度倾斜不再压过地平线建议`() {
        assertEquals("4° 不该抢走地平线建议", "R_HORIZON", hint(tiltedLandscape(4f))?.id)
    }

    @Test
    fun `轻度倾斜不再压过主体建议`() {
        val s = FrameSignals(
            brightness = 132f, sharpness = 300f,
            rollDegrees = 4f, horizonDegrees = 4f,
            subject = NormalizedPoint(0.90f, 0.50f), saliencyConcentration = 0.20f,
        )
        assertEquals("4° 不该抢走主体建议", "R_SUBJECT_OFF", hint(s)?.id)
    }

    @Test
    fun `5 度以内完全不打扰`() {
        // 4.5° < LEVEL_THRESHOLD_DEG(5°) → R_LEVEL 不说话；
        // 其余都正常但总分只有 76（< 85）→ R_GOOD 也沉默。
        // 结论就是**不给提示** —— 倾斜由实时水平基准线兜着，提示位不该被它占用。
        val s = FrameSignals(
            brightness = 132f, sharpness = 300f,
            rollDegrees = 4.5f, saliencyConcentration = 0.01f,
        )
        assertNull("4.5° 的倾斜不值得占用提示位", hint(s))
    }

    @Test
    fun `轻度倾斜且其它都正常时才提示回正`() {
        val s = FrameSignals(
            brightness = 132f, sharpness = 300f,
            rollDegrees = 6f, saliencyConcentration = 0.01f,
        )
        assertEquals("6° 且没别的问题 → 提示回正", "R_LEVEL", hint(s)?.id)
    }

    @Test
    fun `严重倾斜强制压过构图建议`() {
        // 12° > LEVEL_SEVERE_DEG(10°)：照片已经废了，优先级 92 必须压过地平线(65)
        assertEquals("R_LEVEL_SEVERE", hint(tiltedLandscape(12f))?.id)
    }

    @Test
    fun `严重倾斜仍然让位于曝光错误`() {
        // 曝光 95 > 严重倾斜 92：曝光错误更致命
        val s = tiltedLandscape(12f).copy(brightness = 30f)
        assertEquals("R_EXPOSE_LOW", hint(s)?.id)
    }
}
