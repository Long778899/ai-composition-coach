package com.lemon.shotcoach.model

/**
 * 归一化坐标点（0..1 相对画面宽高）。
 *
 * 领域层自定义，刻意不依赖 android.graphics.PointF —— 见 NFR-7：
 * 分析/评分类必须能在 JVM 上直接单元测试。
 */
data class NormalizedPoint(val x: Float, val y: Float) {

    /** 到另一点的欧氏距离（归一化空间） */
    fun distanceTo(other: NormalizedPoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        val CENTER = NormalizedPoint(0.5f, 0.5f)
    }
}

/** 归一化矩形（left/top/right/bottom 均在 0..1） */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val center: NormalizedPoint get() = NormalizedPoint((left + right) / 2f, (top + bottom) / 2f)
    val area: Float get() = (width * height).coerceAtLeast(0f)
}

/**
 * 三分法构图网格线位置（归一化）。三分法：画面被两条水平线 + 两条垂直线分成 3×3。
 */
object RuleOfThirds {
    val LINES = floatArrayOf(1f / 3f, 2f / 3f)

    /** 4 个三分点（交点） */
    val POINTS: List<NormalizedPoint> = buildList {
        for (y in LINES) for (x in LINES) add(NormalizedPoint(x, y))
    }
}
