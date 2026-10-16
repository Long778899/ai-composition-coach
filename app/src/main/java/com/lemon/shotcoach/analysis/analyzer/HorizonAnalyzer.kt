package com.lemon.shotcoach.analysis.analyzer

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2

/**
 * 地平线 / 直线检测（FR-6）。慢通道，每 3 帧，预算 3~5ms。
 *
 * 原理见 docs/ALGORITHMS.md §3.2：Canny 边缘 → 霍夫直线 → 取长度前 N 条 → 中位数倾角。
 * 与传感器通道互校（见 §3.3）。
 *
 * ## v1.1：除了倾角，还输出地平线的**高度**
 *
 * 倾角只用来校平（`FrameSignals.effectiveRoll`）。但拍景物最要紧的一条构图建议是
 * "地平线放在哪" —— 压在三分线上还是压在正中间，观感差别很大。
 * v1.0 算出了倾角却从没用过这条线的**位置**，于是拍风景时一条有效建议都给不出来，
 * 只会反复说"把主体放进画面里"。这个坑见 docs/CHANGELOG.md v1.1.1。
 */
class HorizonAnalyzer {

    /**
     * @param angleDegrees 倾角（度），用于校平
     * @param y 地平线**高度**，归一化 0(画面顶) .. 1(画面底)。null = 无法估计位置
     */
    data class Result(val angleDegrees: Float, val y: Float?)

    val cadence = 3

    /** 参与**倾角**统计的直线：|angle| ≤ 该值（度） */
    private val maxAngleDeg = 60.0

    /**
     * 参与**位置**统计的直线：|angle| ≤ 该值。
     *
     * 比 [maxAngleDeg] 严得多 —— 一条 45° 的斜线可以帮我们校正倾斜，
     * 但它显然不是地平线；拿它的高度去建议"地平线压三分线"就是胡说。
     */
    private val horizonLikeAngleDeg = 30.0

    /** 位置估计至少需要这么多条候选线，单线视为不可信 */
    private val minLinesForPosition = 2

    /** 取长度前 N 条 */
    private val topN = 5

    private val edges = Mat()
    private val lines = Mat()

    private class Candidate(val length: Double, val angleDeg: Double, val midY: Double)

    /**
     * @return 检测结果；没有任何可信直线时返回 null
     */
    fun analyze(gray: Mat): Result? {
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        Imgproc.HoughLinesP(
            edges, lines,
            1.0, Math.PI / 180.0,
            60,      // threshold
            60.0,    // minLineLength
            8.0,     // maxLineGap
        )

        if (lines.rows() == 0) return null

        val candidates = ArrayList<Candidate>(lines.rows())
        for (i in 0 until lines.rows()) {
            val v = lines.get(i, 0) ?: continue
            val x1 = v[0]; val y1 = v[1]; val x2 = v[2]; val y2 = v[3]
            val dx = x2 - x1
            val dy = y2 - y1
            candidates += Candidate(
                length = kotlin.math.hypot(dx, dy),
                angleDeg = normalize(Math.toDegrees(atan2(dy, dx))),
                midY = (y1 + y2) / 2.0,
            )
        }

        val longest = candidates.sortedByDescending { it.length }.take(topN)

        // ── 倾角：近水平（|angle| ≤ 60°）的长线取中位数 ──
        val angles = longest.map { it.angleDeg }.filter { abs(it) <= maxAngleDeg }
        if (angles.isEmpty()) return null
        val angle = angles.sorted()[angles.size / 2].toFloat()

        // ── 高度：更严的近水平判据 + 至少两条候选，否则宁可说"不知道" ──
        val rows = gray.rows()
        val horizonLike = longest.filter { abs(it.angleDeg) <= horizonLikeAngleDeg }
        val y = if (horizonLike.size >= minLinesForPosition && rows > 0) {
            val mids = horizonLike.map { it.midY }.sorted()
            (mids[mids.size / 2] / rows).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }

        return Result(angleDegrees = angle, y = y)
    }

    /** 把角度归一到 -90..90（直线无方向） */
    private fun normalize(deg: Double): Double {
        var d = deg
        while (d > 90.0) d -= 180.0
        while (d <= -90.0) d += 180.0
        return d
    }

    /** 供调试：返回检测到的直线端点（归一化前的像素坐标） */
    fun debugLines(): List<Pair<Point, Point>> {
        val out = ArrayList<Pair<Point, Point>>()
        for (i in 0 until lines.rows()) {
            val v = lines.get(i, 0) ?: continue
            out += Point(v[0], v[1]) to Point(v[2], v[3])
        }
        return out
    }
}
