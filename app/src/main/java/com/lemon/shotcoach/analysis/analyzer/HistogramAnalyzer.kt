package com.lemon.shotcoach.analysis.analyzer

import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfFloat
import org.opencv.imgproc.Imgproc

/**
 * 曝光分析（FR-3）。快通道，每帧运行，预算 <1ms。
 *
 * 原理见 docs/ALGORITHMS.md §2：灰度直方图 → 平均亮度 + 两端 clip 占比。
 */
class HistogramAnalyzer {

    /** 每帧都跑 */
    val cadence = 1

    private val hist = Mat()
    private val histSize = MatOfInt(256)
    private val ranges = MatOfFloat(0f, 256f)

    /** 分析结果 */
    data class Result(
        val meanBrightness: Float,
        val clipLowRatio: Float,
        val clipHighRatio: Float,
    )

    fun analyze(gray: Mat): Result {
        Imgproc.calcHist(listOf(gray), MatOfInt(0), Mat(), hist, histSize, ranges)

        val total = gray.total().toFloat().coerceAtLeast(1f)
        var sum = 0.0
        var low = 0.0
        var high = 0.0
        for (i in 0 until 256) {
            val v = hist.get(i, 0)[0]
            sum += i * v
            if (i <= 10) low += v
            if (i >= 245) high += v
        }

        return Result(
            meanBrightness = (sum / total).toFloat(),
            clipLowRatio = (low / total).toFloat(),
            clipHighRatio = (high / total).toFloat(),
        )
    }
}
