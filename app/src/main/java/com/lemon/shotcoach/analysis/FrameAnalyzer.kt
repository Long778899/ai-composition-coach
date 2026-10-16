package com.lemon.shotcoach.analysis

import com.lemon.shotcoach.analysis.analyzer.FaceAnalyzer
import com.lemon.shotcoach.analysis.analyzer.HistogramAnalyzer
import com.lemon.shotcoach.analysis.analyzer.HorizonAnalyzer
import com.lemon.shotcoach.analysis.analyzer.SaliencyAnalyzer
import com.lemon.shotcoach.analysis.analyzer.SharpnessAnalyzer
import com.lemon.shotcoach.camera.DownScaler
import com.lemon.shotcoach.camera.FrameThrottler
import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.NormalizedPoint
import com.lemon.shotcoach.model.NormalizedRect
import com.lemon.shotcoach.model.OverlayConfig
import com.lemon.shotcoach.model.UiState
import com.lemon.shotcoach.rules.Rule
import com.lemon.shotcoach.rules.RuleEngine
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.ArrayDeque

/**
 * 分析管线调度（FR-1 ~ FR-11 的编排）。
 *
 * 快慢通道分离是本类的核心职责（见 docs/ARCHITECTURE.md §3）：
 *  - 快通道（每帧）：曝光、水平仪（传感器在别处）、清晰度
 *  - 慢通道（每 N 帧）：显著性、地平线、人脸 —— 结果缓存，帧间复用
 *
 * 非线程安全：由 CameraX 的分析 Executor **单线程**持有（见 ARCHITECTURE §2）。
 */
class FrameAnalyzer(
    rules: List<Rule>,
    private val faceAnalyzer: FaceAnalyzer,
    alpha: Float = 0.35f,
    everyN: Int = 3,
    dwellMs: Long = 800L,
    /** 人脸通道开关。关掉可省约 2ms/帧（见设置页）。 */
    private val faceEnabled: Boolean = true,
) {
    /**
     * 叠加层配置，由 UI 线程写入、分析线程读取，随每帧 [UiState] 一并带出。
     *
     * 为什么放在这里：UI 的开关状态需要跟随**每一帧**的状态一起推给界面，
     * 否则关掉网格后要等到下一次 recompose 才生效。这里不做线程同步的原语，
     * 只用一个 @Volatile 引用整体替换 —— 读到的要么是旧值要么是新值，不会读到半个。
     */
    @Volatile
    var overlayConfig: OverlayConfig = OverlayConfig()

    companion object {
        const val CANVAS = 224
        const val FACE_CANVAS = 320
    }

    private val downScaler = DownScaler(CANVAS)
    private val faceScaler = DownScaler(FACE_CANVAS)

    private val histogram = HistogramAnalyzer()
    private val sharpness = SharpnessAnalyzer()
    private val horizon = HorizonAnalyzer()
    private val saliency = SaliencyAnalyzer()

    private val smoother = EmaSmoother(alpha)
    private val engine = RuleEngine(rules, dwellMs)
    private val throttler = FrameThrottler(everyN)

    // 复用的中间 Mat（NFR-2：零每帧分配）
    private val gray224 = Mat()
    private val gray320 = Mat()

    // 慢通道缓存
    private var cachedSharpness = 0f
    private var cachedSaliency: SaliencyAnalyzer.Result? = null
    private var cachedHorizon: Float? = null
    private var cachedFaces: List<NormalizedRect> = emptyList()

    // FPS / 延迟统计
    private val frameTimes = ArrayDeque<Long>()
    private var lastUiState: UiState = UiState()

    /**
     * @param rgba  输入帧（RGBA_8888）
     * @param roll  传感器倾角（度），来自 [com.lemon.shotcoach.analysis.analyzer.OrientationAnalyzer]
     * @param nowMs 当前时间戳（毫秒）
     * @return 新的 UI 状态；本帧被抽帧跳过则返回 null
     */
    fun analyze(rgba: Mat, roll: Float, nowMs: Long): UiState? {
        if (!throttler.shouldAnalyze()) return null
        throttler.beginAnalysis()
        try {
            val t0 = System.nanoTime()

            // ── FR-2 降采样 ─────────────────────────────────
            val out = downScaler.scale(rgba)
            // 注意：用 out.content（去掉 letterbox 黑边的内容视图），绝不能用 out.mat。
            // 黑边是纯 0 像素，参与直方图统计会被当成"欠曝像素"——
            // 4:3 画幅占 25%、16:9 占 44%，直接超过 clipLowRatio 阈值 0.15，
            // 结果"增加曝光"成为永远命中的建议。见 docs/CHANGELOG.md v1.0.1。
            Imgproc.cvtColor(out.content, gray224, Imgproc.COLOR_RGBA2GRAY)

            // ── FR-3 曝光（快通道，每帧）──────────────────────
            val h = histogram.analyze(gray224)

            // ── FR-8 清晰度（每 N 帧）────────────────────────
            if (throttler.dueFor(sharpness.cadence)) {
                cachedSharpness = sharpness.analyze(gray224)
            }

            // ── FR-5 显著性（每 N 帧）────────────────────────
            if (throttler.dueFor(saliency.cadence)) {
                cachedSaliency = saliency.analyze(gray224)
            }

            // ── FR-6 地平线（每 N 帧）────────────────────────
            if (throttler.dueFor(horizon.cadence)) {
                cachedHorizon = horizon.analyze(gray224)
            }

            // ── FR-7 人脸（每 N 帧，独立 320 画布）────────────
            if (faceEnabled && throttler.dueFor(faceAnalyzer.cadence)) {
                val fout = faceScaler.scale(rgba)
                Imgproc.cvtColor(fout.content, gray320, Imgproc.COLOR_RGBA2GRAY)
                // 在内容视图上检测 → 返回的已是"画面归一化"坐标，无需再映射
                cachedFaces = faceAnalyzer.analyze(gray320)
            }

            // ── 组装 FrameSignals（把所有坐标映射回归一化画面）──
            // 显著性在内容视图上计算 → cx/cy 已是相对画面内容的归一化坐标，直接用
            val subject = cachedSaliency?.let { NormalizedPoint(it.cx, it.cy) }

            val raw = FrameSignals(
                brightness = h.meanBrightness,
                clipLowRatio = h.clipLowRatio,
                clipHighRatio = h.clipHighRatio,
                subject = subject,
                saliencyConcentration = cachedSaliency?.concentration ?: 0f,
                rollDegrees = roll,
                horizonDegrees = cachedHorizon,
                faces = cachedFaces,
                sharpness = cachedSharpness,
                timestampMs = nowMs,
            )

            // ── FR-10 平滑 ──────────────────────────────────
            val smoothed = smoother.smooth(raw)

            // ── FR-9 评分 ───────────────────────────────────
            val score = CompositionScorer.score(smoothed)

            // ── FR-11 规则 → 单条提示 ────────────────────────
            val hint = engine.best(smoothed, score, nowMs)

            // ── 统计 ────────────────────────────────────────
            frameTimes.addLast(nowMs)
            while (frameTimes.size > 30) frameTimes.removeFirst()
            val fps = if (frameTimes.size >= 2) {
                val span = (frameTimes.last() - frameTimes.first()).coerceAtLeast(1L)
                (frameTimes.size - 1) * 1000f / span
            } else 0f

            val latency = (System.nanoTime() - t0) / 1_000_000f

            val cfg = overlayConfig
            lastUiState = UiState(
                hint = hint,
                score = score,
                subjectPoint = smoothed.subject,
                rollDegrees = smoothed.effectiveRoll,
                faceRects = smoothed.faces,
                fps = fps,
                latencyMs = latency,
                gridStyle = cfg.gridStyle,
                gridEnabled = cfg.gridEnabled,
                levelEnabled = cfg.levelEnabled,
                guideEnabled = cfg.guideEnabled,
                scoreEnabled = cfg.scoreEnabled,
            )
            return lastUiState
        } finally {
            throttler.endAnalysis()
        }
    }

    fun reset() {
        throttler.reset()
        smoother.reset()
        engine.reset()
        sharpness.reset()
        frameTimes.clear()
        cachedSaliency = null
        cachedHorizon = null
        cachedFaces = emptyList()
        cachedSharpness = 0f
    }

    fun release() {
        downScaler.release()
        faceScaler.release()
        gray224.release()
        gray320.release()
    }

    /** 供调试/测试：Android Bitmap → RGBA Mat */
    fun bitmapToMat(bitmap: android.graphics.Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }
}
