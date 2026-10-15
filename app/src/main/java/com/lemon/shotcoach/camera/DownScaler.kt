package com.lemon.shotcoach.camera

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 降采样（FR-2）。见 docs/ALGORITHMS.md §1。
 *
 * 关键点（NFR-2 零每帧分配）：
 *  - 预分配目标 Mat，跨帧复用；
 *  - 采用 letterbox（保持宽高比 + 黑边），避免主体坐标因拉伸而失真；
 *  - 用 INTER_AREA 插值（缩小场景最优）。
 *
 * 非线程安全：由分析线程单线程持有。
 */
class DownScaler(private val targetLongSide: Int = 224) {

    private var dst: Mat? = null
    private var roi: Mat? = null
    private var letterbox: Rect = Rect()
    private var scale: Float = 1f

    data class Output(
        val mat: Mat,
        /** 有效内容区域（去掉黑边），据此把坐标映射回归一化空间 */
        val contentRect: Rect,
        /** 原图 → 目标图的缩放比 */
        val scale: Float,
    )

    fun scale(src: Mat): Output {
        val w = src.cols()
        val h = src.rows()
        if (w == 0 || h == 0) return Output(src, Rect(0, 0, 0, 0), 1f)

        val ratio = targetLongSide.toFloat() / maxOf(w, h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)

        val out = dst ?: Mat().also { dst = it }
        // 全画布（targetLongSide × targetLongSide）
        if (out.empty() || out.cols() != targetLongSide || out.rows() != targetLongSide) {
            out.create(Size(targetLongSide.toDouble(), targetLongSide.toDouble()), src.type())
        }
        out.setTo(org.opencv.core.Scalar(0.0))   // 黑边

        // 居中 letterbox（roi 复用，避免每帧新建 Mat 视图）
        val x = (targetLongSide - nw) / 2
        val y = (targetLongSide - nh) / 2
        val r = roi ?: out.submat(Rect(x, y, nw, nh)).also { roi = it }
        Imgproc.resize(src, r, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

        letterbox = Rect(x, y, nw, nh)
        scale = ratio
        return Output(out, letterbox, ratio)
    }

    /**
     * 把目标图上的像素坐标映射回归一化 (0..1) 画面坐标。
     * 分析器在 letterbox 图上算出的点必须经此转换才能给 UI 用。
     */
    fun toNormalized(px: Float, py: Float): Pair<Float, Float> {
        val lx = (px - letterbox.x) / letterbox.width
        val ly = (py - letterbox.y) / letterbox.height
        return lx.coerceIn(0f, 1f) to ly.coerceIn(0f, 1f)
    }

    fun release() {
        dst?.release()
        dst = null
        roi?.release()
        roi = null
    }
}
