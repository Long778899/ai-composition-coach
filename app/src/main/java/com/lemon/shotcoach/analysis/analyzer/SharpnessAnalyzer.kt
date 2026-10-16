package com.lemon.shotcoach.analysis.analyzer

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/**
 * 清晰度 / 抖动检测（FR-8）。慢通道，每 3 帧，预算 <1ms。
 *
 * 原理见 docs/ALGORITHMS.md §6：Laplacian 方差。清晰图边缘锐利 → 二阶导数响应强 → 方差大。
 *
 * 关键是**自适应阈值**：不同场景纹理差异极大（白墙 vs 树林），固定阈值必然误报。
 * 本实现维护最近 [HISTORY] 帧的方差滑动窗口，低于历史 25 分位才认为异常。
 */
class SharpnessAnalyzer {

    val cadence = 3

    private val lap = Mat()

    // Core.meanStdDev 的 Java 签名要求 MatOfDouble（不是 Mat）。
    // v1.0 这里写成了 Mat —— 仓库从没编译过，所以这个类型错误一直没暴露。
    // 2026-10 引入真 Kotlin 编译器做校验时才发现并修掉。
    private val mean = MatOfDouble()
    private val stddev = MatOfDouble()

    private val history = ArrayDeque<Float>()
    private val historyCap: Int

    constructor(historyCap: Int = 60) {
        this.historyCap = historyCap
    }

    /** @return Laplacian 方差 */
    fun analyze(gray: Mat): Float {
        Imgproc.Laplacian(gray, lap, CvType.CV_64F, 3)
        Core.meanStdDev(lap, mean, stddev)
        val sd = stddev.get(0, 0)[0]
        val variance = (sd * sd).toFloat()

        history.addLast(variance)
        while (history.size > historyCap) history.removeFirst()

        return variance
    }

    /** 自适应阈值：历史 25 分位。样本不足（< 10）时返回 0（不告警） */
    fun adaptiveThreshold(): Float {
        if (history.size < 10) return 0f
        val sorted = history.toList().sorted()
        return sorted[(sorted.size * 0.25f).toInt().coerceIn(0, sorted.size - 1)]
    }

    /** 是否低于自适应阈值（疑似失焦/抖动） */
    fun isLikelyBlurry(current: Float): Boolean {
        val t = adaptiveThreshold()
        return t > 0f && current < t
    }

    fun reset() = history.clear()
}
