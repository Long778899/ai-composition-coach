package com.lemon.shotcoach

import com.lemon.shotcoach.analysis.CompositionScorer
import com.lemon.shotcoach.analysis.EmaSmoother
import com.lemon.shotcoach.model.AiChannel
import com.lemon.shotcoach.model.CompositionScore
import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.NormalizedPoint
import com.lemon.shotcoach.model.NormalizedRect
import com.lemon.shotcoach.rules.AestheticLowRule
import com.lemon.shotcoach.rules.SubjectOffRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端侧 AI 通道接入（v1.1）的纯逻辑回归。
 *
 * 这里刻意**不碰任何 Android / MediaPipe / TFLite 类** —— 全部断言围绕
 * [FrameSignals] 这个唯一契约展开，所以能在纯 JVM 上跑（非仪器化测试）。
 *
 * 覆盖三条最要紧的性质：
 *  1. **AI 关闭时逐位兼容 v1.0**（不能因为接入 AI 让老行为漂移）
 *  2. **AI 主体框确实取代了"偏中心"的显著性质心**（这是接 AI 的动机）
 *  3. **AI 未启用时 AI 规则不触发**（而不是永远触发/永远不触发）
 */
class AiCompositionTest {

    private val resolve: (String) -> String = { key -> "<$key>" }

    /** 一个"曝光/水平/清晰度全满"的基准信号，用来把变量收敛到主体与美感两项上。 */
    private fun perfectBase(
        subject: NormalizedPoint? = null,
        subjectBox: NormalizedRect? = null,
        aestheticScore: Float? = null,
    ) = FrameSignals(
        brightness = 130f,          // 理想亮度 → 曝光 100
        clipLowRatio = 0f,
        clipHighRatio = 0f,
        subject = subject,
        saliencyConcentration = 0f,
        rollDegrees = 0f,           // 水平 → 水平分 100
        horizonDegrees = null,
        faces = emptyList(),
        sharpness = 220f,           // 参考方差 → 清晰度 100
        subjectBox = subjectBox,
        aestheticScore = aestheticScore,
    )

    private val thirdsX = 1f / 3f
    private val thirdsY = 1f / 3f

    /** 中心恰好落在 (1/3, 1/3) 三分点的检测框 */
    private fun boxAtThirds() = NormalizedRect(
        left = thirdsX - 0.05f,
        top = thirdsY - 0.05f,
        right = thirdsX + 0.05f,
        bottom = thirdsY + 0.05f,
    )

    // ── 1. 向后兼容 ──────────────────────────────────────────

    @Test
    fun `AI 关闭时总分与 v1_0 手算结果一致`() {
        // 无主体 → 主体分兜底 60；曝光/水平/清晰度全满
        val s = perfectBase()
        val score = CompositionScorer.score(s)

        // 60*0.40 + 100*0.25 + 100*0.20 + 100*0.15 = 24 + 25 + 20 + 15 = 84
        assertEquals(84, score.total)
        assertNull("AI 未启用时美感分必须为 null", score.aestheticScore)
        assertEquals(60, score.subjectScore)
    }

    @Test
    fun `AI 关闭时审美维度不参与最差子项评选`() {
        val score = CompositionScorer.score(perfectBase())
        // 四路子项里最差的是主体(60)，AESTHETIC 不该冒出来
        assertEquals(CompositionScore.Aspect.SUBJECT, score.worstAspect)
    }

    @Test
    fun `AI 关闭时四路权重仍是 40_25_20_15`() {
        assertEquals(0.40f, CompositionScore.W_SUBJECT)
        assertEquals(0.25f, CompositionScore.W_LEVEL)
        assertEquals(0.20f, CompositionScore.W_EXPOSURE)
        assertEquals(0.15f, CompositionScore.W_SHARPNESS)
    }

    // ── 2. 美感分映射与权重 ──────────────────────────────────

    @Test
    fun `NIMA 分数线性映射到 0_100`() {
        assertEquals(0, CompositionScore.aestheticToScore(1f))
        assertEquals(100, CompositionScore.aestheticToScore(10f))
        assertEquals(50, CompositionScore.aestheticToScore(5.5f))
        // 越界要夹紧而不是溢出
        assertEquals(0, CompositionScore.aestheticToScore(-3f))
        assertEquals(100, CompositionScore.aestheticToScore(42f))
    }

    @Test
    fun `有美感分时启用第二套权重且占 25%`() {
        // 注意：主体必须走 AI 检测框。传 subject=点 是不行的 —— saliencyConcentration=0 时
        // 纯 CV 路径会判定"没有可信主体"直接兜底 60 分（这正是 v1.0 的门控语义）。
        // 其余四项全满(100)，美感分给最低(1.0 → 0 分)
        val s = perfectBase(subjectBox = boxAtThirds(), aestheticScore = 1f)
        val score = CompositionScorer.score(s)

        // 100*0.30 + 100*0.18 + 100*0.15 + 100*0.12 + 0*0.25 = 75
        assertEquals(75, score.total)
        assertEquals(0, score.aestheticScore)
        assertNotNull(score.aestheticScore)
    }

    @Test
    fun `美感分极低时会被选为最差子项`() {
        val s = perfectBase(subjectBox = boxAtThirds(), aestheticScore = 1f)
        val score = CompositionScorer.score(s)
        // 唯一扣分项是美感(扣满 25)，其余扣 0
        assertEquals(CompositionScore.Aspect.AESTHETIC, score.worstAspect)
    }

    @Test
    fun `AI 权重集必须归一到 1`() {
        val sum = CompositionScore.W_SUBJECT_AI +
            CompositionScore.W_LEVEL_AI +
            CompositionScore.W_EXPOSURE_AI +
            CompositionScore.W_SHARPNESS_AI +
            CompositionScore.W_AESTHETIC_AI
        assertEquals(1.0f, sum, 1e-6f)

        val sumV1 = CompositionScore.W_SUBJECT +
            CompositionScore.W_LEVEL +
            CompositionScore.W_EXPOSURE +
            CompositionScore.W_SHARPNESS
        assertEquals(1.0f, sumV1, 1e-6f)
    }

    // ── 3. AI 主体框取代显著性质心 ───────────────────────────

    @Test
    fun `effectiveSubject 优先取 AI 检测框中心`() {
        val s = FrameSignals(
            subject = NormalizedPoint(0.9f, 0.9f),   // 显著性说主体在右下角
            saliencyConcentration = 0.5f,
            subjectBox = NormalizedRect(0.1f, 0.2f, 0.3f, 0.4f),  // AI 说在左上
        )
        val eff = s.effectiveSubject
        assertNotNull(eff)
        assertEquals(0.2f, eff!!.x, 1e-6f)
        assertEquals(0.3f, eff.y, 1e-6f)
        assertTrue(s.hasAiSubject)
    }

    @Test
    fun `无 AI 时 effectiveSubject 退回显著性质心`() {
        val s = FrameSignals(subject = NormalizedPoint(0.9f, 0.9f), saliencyConcentration = 0.5f)
        assertEquals(NormalizedPoint(0.9f, 0.9f), s.effectiveSubject)
        assertFalse(s.hasAiSubject)
    }

    @Test
    fun `AI 主体框在三分点时得高分且不受显著性浓度门槛限制`() {
        // saliencyConcentration = 0 且 subject = null —— 纯 CV 路径会直接兜底 60 分
        val cvOnly = perfectBase()
        assertEquals(60, CompositionScorer.subjectScore(cvOnly))

        // 换成 AI 检测框，中心落在三分点上 → 应得接近满分
        val withAi = perfectBase(subjectBox = boxAtThirds())
        assertTrue(
            "AI 主体框在三分点应得高分，实际 ${CompositionScorer.subjectScore(withAi)}",
            CompositionScorer.subjectScore(withAi) >= 95,
        )
    }

    @Test
    fun `主体偏离时 AI 主体框给出的方向提示与偏移方向一致`() {
        // 主体框中心在画面左侧、偏上 → 最近的合法点是左三分点，偏差主要在 x 轴
        val box = NormalizedRect(0.02f, 0.30f, 0.14f, 0.42f)   // 中心 (0.08, 0.36)
        val s = perfectBase(subjectBox = box)
        val m = SubjectOffRule(resolve).match(s, CompositionScorer.score(s))
        assertNotNull("偏离三分点应触发主体提示", m)
        assertEquals("hint_subject_right", m!!.textKey)
    }

    @Test
    fun `主体框中心已落在三分点上时主体规则不触发`() {
        val s = perfectBase(subjectBox = boxAtThirds())
        val m = SubjectOffRule(resolve).match(s, CompositionScorer.score(s))
        assertNull("已经构图正确就不该再报偏移", m)
    }

    // ── 4. AI 规则的门控 ─────────────────────────────────────

    @Test
    fun `美感规则只在美感分存在时参与`() {
        val rule = AestheticLowRule(resolve)

        // 没启用 AI 美感通道 → 不触发（不是"永远触发"）
        assertNull(rule.match(perfectBase(), CompositionScorer.score(perfectBase())))

        // 4.0 分（低于阈值 5.0）→ 触发
        val low = perfectBase(aestheticScore = 4.0f)
        val lowMatch = rule.match(low, CompositionScorer.score(low))
        assertNotNull(lowMatch)
        assertEquals("hint_aesthetic_low", lowMatch!!.textKey)

        // 6.5 分 → 不触发
        val ok = perfectBase(aestheticScore = 6.5f)
        assertNull(rule.match(ok, CompositionScorer.score(ok)))
    }

    // ── 5. 通道自述（可观测性）───────────────────────────────

    @Test
    fun `aiChannels 如实报告本帧走了哪些神经网络`() {
        val none = perfectBase()
        assertTrue(none.aiChannels.isEmpty())

        val subject = perfectBase(subjectBox = boxAtThirds())
        assertEquals(setOf(AiChannel.SUBJECT), subject.aiChannels)

        val aes = perfectBase(aestheticScore = 7f)
        assertEquals(setOf(AiChannel.AESTHETIC), aes.aiChannels)

        val both = perfectBase(subjectBox = boxAtThirds(), aestheticScore = 7f)
        assertEquals(setOf(AiChannel.SUBJECT, AiChannel.AESTHETIC), both.aiChannels)
    }

    @Test
    fun `人脸只有确认来自 AI 且非空时才计入 AI 通道`() {
        val haarFace = NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f)
        val fromHaar = perfectBase().copy(faces = listOf(haarFace), facesFromAi = false)
        assertFalse(AiChannel.FACE in fromHaar.aiChannels)

        val fromAi = perfectBase().copy(faces = listOf(haarFace), facesFromAi = true)
        assertTrue(AiChannel.FACE in fromAi.aiChannels)

        // 标了 AI 但这一帧没检出脸 → 不该谎报
        val aiEmpty = perfectBase().copy(faces = emptyList(), facesFromAi = true)
        assertFalse(AiChannel.FACE in aiEmpty.aiChannels)
    }

    // ── 6. 平滑：null 不等于 0 ───────────────────────────────

    @Test
    fun `美感分在抽帧间隙保持上一次的值而不是清零`() {
        val smoother = EmaSmoother()
        val first = smoother.smooth(perfectBase(aestheticScore = 6f))
        assertEquals(6f, first.aestheticScore)

        // 下一帧美感通道没跑（cadence 未到）→ aestheticScore = null
        val second = smoother.smooth(perfectBase(aestheticScore = null))
        assertEquals("null 表示没测，不是测得 0", 6f, second.aestheticScore)

        // 再来一个真实测量值 → 做 EMA 而不是跳变
        val third = smoother.smooth(perfectBase(aestheticScore = 8f))
        assertTrue((third.aestheticScore ?: 0f) in 6f..8f)
    }

    @Test
    fun `主体框在平滑器里做 EMA 且重置后清空`() {
        val smoother = EmaSmoother()
        smoother.smooth(perfectBase(subjectBox = NormalizedRect(0f, 0f, 0.2f, 0.2f)))
        val s2 = smoother.smooth(perfectBase(subjectBox = NormalizedRect(0.8f, 0.8f, 1f, 1f)))
        val b2 = s2.subjectBox
        assertNotNull(b2)
        // 首帧直接取 x，第二帧才 EMA(0.35)：0.35*0.8 + 0.65*0.0 = 0.28
        assertEquals(0.28f, b2!!.left, 1e-4f)

        smoother.reset()
        val s3 = smoother.smooth(perfectBase(subjectBox = NormalizedRect(0.8f, 0.8f, 1f, 1f)))
        assertEquals("重置后首帧直接取原值", 0.8f, s3.subjectBox!!.left, 1e-6f)
    }
}
