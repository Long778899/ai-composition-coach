package com.lemon.shotcoach.analysis.analyzer

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * 主体显著性定位（FR-5）。慢通道，每 3 帧，预算 3~6ms。
 *
 * 两条实现路径（见 docs/ALGORITHMS.md §4）：
 *  - [Mode.SPECTRAL_RESIDUAL]：OpenCV `saliency` 模块（若已编译）
 *  - [Mode.GRADIENT_CENTER]：中心先验 + 梯度能量（纯 imgproc 自实现，无额外依赖）
 *
 * 构造时探测可用性自动选择；也可强制指定，便于测试。
 */
class SaliencyAnalyzer(
    val mode: Mode = detectBestMode(),
) {
    enum class Mode { SPECTRAL_RESIDUAL, GRADIENT_CENTER }

    val cadence = 3

    private val gx = Mat()
    private val gy = Mat()
    private val mag = Mat()
    private val blurred = Mat()

    data class Result(
        /** 显著性质心，归一化 0..1 */
        val cx: Float,
        val cy: Float,
        /** 集中度：阈值内像素占比 0..1 */
        val concentration: Float,
    )

    fun analyze(gray: Mat): Result {
        return when (mode) {
            Mode.SPECTRAL_RESIDUAL -> spectralResidual(gray)
            Mode.GRADIENT_CENTER -> gradientCenterPrior(gray)
        }
    }

    // ── 路径 A：频域残差 ──────────────────────────────────────
    private fun spectralResidual(gray: Mat): Result {
        // 注：需 OpenCV contrib 的 saliency 模块。
        // 为保持本工程对 OpenCV 构建的最小依赖，此处走与路径 B 相同的接口，
        // 实际项目可按需替换为 Saliency.createStaticSaliencySpectralResidual()。
        return gradientCenterPrior(gray)
    }

    // ── 路径 B：中心先验 + 梯度能量 ───────────────────────────
    private fun gradientCenterPrior(gray: Mat): Result {
        Imgproc.GaussianBlur(gray, blurred, org.opencv.core.Size(5.0, 5.0), 0.0)

        Imgproc.Sobel(blurred, gx, CvType.CV_32F, 1, 0)
        Imgproc.Sobel(blurred, gy, CvType.CV_32F, 0, 1)
        Core.magnitude(gx, gy, mag)
        Core.normalize(mag, mag, 0.0, 1.0, Core.NORM_MINMAX, CvType.CV_32F)

        // 中心先验（高斯权重）：σ = 0.35 × min(w,h)
        val w = gray.cols()
        val h = gray.rows()
        val sigma = 0.35 * minOf(w, h)
        val cx0 = w / 2.0
        val cy0 = h / 2.0
        val twoSigmaSq = 2.0 * sigma * sigma

        // 合成显著性图并求加权质心
        var sumW = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var kw = 0.0
        val n = IntArray(1)
        var aboveThresh = 0
        var totalPx = 0

        // 稀疏采样（每 2 像素），保证 <6ms 预算
        val step = 2
        val magBuf = FloatArray(1)
        for (y in 0 until h step step) {
            val dy = y - cy0
            for (x in 0 until w step step) {
                val dx = x - cx0
                val centerPrior = kotlin.math.exp(-(dx * dx + dy * dy) / twoSigmaSq).toFloat()

                mag.get(y, x, magBuf)
                val s = 0.6f * magBuf[0] + 0.4f * centerPrior

                if (s > 0.05f) { // 阈值：忽略纯背景
                    sumW += s
                    sumX += s * x
                    sumY += s * y
                    kw += centerPrior
                }
                totalPx++
                if (s > 0.6f) aboveThresh++
            }
        }

        if (sumW <= 1e-6 || totalPx == 0) {
            return Result(0.5f, 0.5f, 0f)
        }

        return Result(
            cx = (sumX / sumW / w).toFloat(),
            cy = (sumY / sumW / h).toFloat(),
            concentration = (aboveThresh.toFloat() / totalPx).coerceIn(0f, 1f),
        )
    }

    companion object {
        /** 探测 OpenCV 构建是否包含 saliency 模块 */
        fun detectBestMode(): Mode {
            return try {
                Class.forName("org.opencv.saliency.Saliency")
                Mode.SPECTRAL_RESIDUAL
            } catch (_: Throwable) {
                Mode.GRADIENT_CENTER
            }
        }
    }
}
