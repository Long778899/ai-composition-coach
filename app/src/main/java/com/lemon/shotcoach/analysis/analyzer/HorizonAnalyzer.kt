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
 */
class HorizonAnalyzer {

    val cadence = 3

    /** 只考虑近水平直线：|angle| ≤ 该值（度） */
    private val maxAngleDeg = 60.0

    /** 取长度前 N 条 */
    private val topN = 5

    private val edges = Mat()
    private val lines = Mat()

    /**
     * @return 视觉地平线倾角（度）；不可信时返回 null
     */
    fun analyze(gray: Mat): Float? {
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        Imgproc.HoughLinesP(
            edges, lines,
            1.0, Math.PI / 180.0,
            60,      // threshold
            60.0,    // minLineLength
            8.0,     // maxLineGap
        )

        if (lines.rows() == 0) return null

        val candidates = ArrayList<Pair<Double, Double>>(lines.rows()) // (length, angle)
        for (i in 0 until lines.rows()) {
            val v = lines.get(i, 0) ?: continue
            val x1 = v[0]; val y1 = v[1]; val x2 = v[2]; val y2 = v[3]
            val dx = x2 - x1
            val dy = y2 - y1
            val len = kotlin.math.hypot(dx, dy)
            val angle = Math.toDegrees(atan2(dy, dx))       // -180..180
            candidates += len to angle
        }

        val angles = candidates
            .sortedByDescending { it.first }
            .take(topN)
            .map { normalize(it.second) }
            .filter { abs(it) <= maxAngleDeg }             // 过滤近垂直线（建筑/树干）

        if (angles.isEmpty()) return null

        val median = angles.sorted()[angles.size / 2]
        return median.toFloat()
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
