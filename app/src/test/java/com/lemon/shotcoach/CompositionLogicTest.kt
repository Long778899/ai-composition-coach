package com.lemon.shotcoach

import com.lemon.shotcoach.analysis.CompositionScorer
import com.lemon.shotcoach.analysis.EmaSmoother
import com.lemon.shotcoach.model.CompositionScore
import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.NormalizedPoint
import com.lemon.shotcoach.rules.BuiltinRules
import com.lemon.shotcoach.rules.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 领域层单元测试（NFR-7 / AC-09）。
 *
 * 这些类不依赖 Android，因此可以在纯 JVM 上直接验证 —— 这是分层设计的直接收益。
 * `./gradlew test`
 */
class CompositionLogicTest {

    private val texts = mapOf(
        "hint_level_left" to "相机向左倾斜一点",
        "hint_level_right" to "相机向右倾斜一点",
        "hint_expose_low" to "画面偏暗，增加曝光",
        "hint_expose_high" to "画面偏亮，降低曝光",
        "hint_subject_left" to "把主体往左移一点",
        "hint_subject_right" to "把主体往右移一点",
        "hint_subject_up" to "把主体往上移一点",
        "hint_subject_down" to "把主体往下移一点",
        "hint_blur" to "可能失焦，稳住手机",
        "hint_face_small" to "靠近一点",
        "hint_face_none" to "把主体放进画面里",
        "hint_good" to "构图不错，可以按了",
    )

    private fun engine() = RuleEngine(BuiltinRules.all { texts[it] ?: it })

    private fun good() = FrameSignals(
        brightness = 130f,
        subject = NormalizedPoint(1f / 3f, 1f / 3f),   // 正好在三分点
        saliencyConcentration = 0.2f,
        rollDegrees = 0f,
        sharpness = 500f,
    )

    // ── 评分 ──────────────────────────────────────────────

    @Test
    fun `完美构图的各项分数应接近满分`() {
        val s = CompositionScorer.score(good())
        assertTrue("subject=${s.subjectScore}", s.subjectScore >= 95)
        assertEquals(100, s.levelScore)
        assertEquals(100, s.exposureScore)
        assertTrue(s.total >= 95)
    }

    @Test
    fun `水平分 — 每度扣12分`() {
        assertEquals(100, CompositionScorer.levelScore(0f))
        assertEquals(88, CompositionScorer.levelScore(1f))
        assertEquals(64, CompositionScorer.levelScore(3f))
        assertEquals(0, CompositionScorer.levelScore(10f))
    }

    @Test
    fun `曝光分 — 偏离适中亮度扣分`() {
        assertEquals(100, CompositionScorer.exposureScore(130f))
        assertEquals(72, CompositionScorer.exposureScore(150f))
        assertTrue(CompositionScorer.exposureScore(40f) < 20)
    }

    @Test
    fun `主体在角落时主体分应很低`() {
        val s = FrameSignals(
            brightness = 130f,
            subject = NormalizedPoint(0.02f, 0.02f),
            saliencyConcentration = 0.3f,
        )
        val score = CompositionScorer.score(s)
        assertTrue("score=${score.subjectScore}", score.subjectScore < 70)
        assertEquals(CompositionScore.Aspect.SUBJECT, score.worstAspect)
    }

    // ── 规则 ──────────────────────────────────────────────

    @Test
    fun `画面倾斜时命中水平规则`() {
        val s = good().copy(rollDegrees = 6f)
        val hint = engine().best(s, CompositionScorer.score(s), 1000L)
        assertNotNull(hint)
        assertEquals("R_LEVEL", hint!!.id)
    }

    @Test
    fun `曝光错误优先级高于水平`() {
        val s = good().copy(brightness = 30f, rollDegrees = 6f)
        val hint = engine().best(s, CompositionScorer.score(s), 1000L)
        assertEquals("R_EXPOSE_LOW", hint!!.id)   // 曝光优先级 95 > 水平 90
    }

    @Test
    fun `驻留约束 — 800ms 内不允许切换提示`() {
        val e = engine()

        val tilted = good().copy(rollDegrees = 6f)
        val h1 = e.best(tilted, CompositionScorer.score(tilted), 0L)
        assertEquals("R_LEVEL", h1!!.id)

        // 100ms 后画面变暗，但驻留未满 → 仍返回旧提示
        val dark = good().copy(rollDegrees = 6f, brightness = 20f)
        val h2 = e.best(dark, CompositionScorer.score(dark), 100L)
        assertEquals("R_LEVEL", h2!!.id)

        // 900ms 后 → 允许切换
        val h3 = e.best(dark, CompositionScorer.score(dark), 900L)
        assertEquals("R_EXPOSE_LOW", h3!!.id)
    }

    @Test
    fun `构图完美时给正反馈`() {
        val s = good()
        val hint = engine().best(s, CompositionScorer.score(s), 0L)
        assertEquals("R_GOOD", hint!!.id)
    }

    // ── 平滑 ──────────────────────────────────────────────

    @Test
    fun `EMA 首帧直接取原值`() {
        val smoother = EmaSmoother(0.35f)
        val out = smoother.smooth(FrameSignals(brightness = 200f, rollDegrees = 10f))
        assertEquals(200f, out.brightness, 0.01f)
        assertEquals(10f, out.rollDegrees, 0.01f)
    }

    @Test
    fun `EMA 逐帧收敛且不突变`() {
        val smoother = EmaSmoother(0.35f)
        smoother.smooth(FrameSignals(brightness = 100f))
        val out = smoother.smooth(FrameSignals(brightness = 200f))
        // 100 + 0.35*(200-100) = 135
        assertEquals(135f, out.brightness, 0.5f)
    }

    @Test
    fun `EMA 抑制主体坐标抖动`() {
        val smoother = EmaSmoother(0.35f)
        smoother.smooth(FrameSignals(subject = NormalizedPoint(0.5f, 0.5f)))
        val out = smoother.smooth(FrameSignals(subject = NormalizedPoint(0.6f, 0.5f)))
        val x = out.subject!!.x
        assertTrue("x=$x 应在 0.5~0.6 之间", x > 0.5f && x < 0.6f)
    }

    // ── 融合 ──────────────────────────────────────────────

    @Test
    fun `视觉地平线与传感器一致时用传感器值`() {
        val s = FrameSignals(rollDegrees = 5f, horizonDegrees = 5.5f)
        assertEquals(5f, s.effectiveRoll, 0.01f)
    }

    @Test
    fun `视觉地平线与传感器分歧大时用视觉值`() {
        val s = FrameSignals(rollDegrees = 0f, horizonDegrees = 12f)
        assertEquals(12f, s.effectiveRoll, 0.01f)
    }

    @Test
    fun `无主体无评分时不应崩溃`() {
        val s = FrameSignals()
        val score = CompositionScorer.score(s)
        val hint = engine().best(s, score, 0L)
        assertTrue(score.total in 0..100)
        assertNull(hint?.takeIf { it.id == "R_GOOD" })   // 空画面不可能满分
    }
}
