package com.lemon.shotcoach.filter

/**
 * 滤镜缩略图用的**程序化色卡**。纯 Kotlin，可单测。
 *
 * == 为什么不直接拿取景画面当缩略图 ==
 * 两个原因：
 *  1. 页面刚打开、相机还没出帧时要能立刻显示 —— 不能白屏。
 *  2. 一面墙的缩略图如果都是"当前场景"，用户分辨不出滤镜差异；
 *     色卡里有肤色、天空、绿叶、白点和高光渐变，色偏一眼可见。
 * 相机出帧后会替换成真实画面（见 FilterStrip 的 baseSwatch 参数）。
 */
object SwatchPattern {

    /**
     * 生成 size×size 的 ARGB 色卡。
     *
     * 版面（水平四段 × 垂直三段）：
     * ```
     * ┌──────────┬──────────┬──────────┬──────────┐
     * │ 灰阶 4 档            │ 高光/阴影渐变        │
     * ├──────────┼──────────┼──────────┼──────────┤
     * │ 肤色  │ 天空蓝 │ 绿叶绿 │ 暖黄光 │
     * ├──────────┼──────────┼──────────┼──────────┤
     * │ 饱和色轮（红→青）                        │
     * └──────────┴──────────┴──────────┴──────────┘
     * ```
     */
    fun build(size: Int, out: IntArray? = null): IntArray = build(size, size, out)

    /**
     * 生成 w×h 的 ARGB 色卡（非正方形也支持 —— 边框缩略图需要 4:3）。
     */
    fun build(w: Int, h: Int, out: IntArray? = null): IntArray {
        val n = w
        val px = if (out != null && out.size == n * h) out else IntArray(n * h)
        val row = h / 3

        for (y in 0 until h) {
            val band = when {
                y < row -> 0        // 上：灰阶 + 高光渐变
                y < row * 2 -> 1    // 中：记忆色
                else -> 2           // 下：色轮
            }
            for (x in 0 until n) {
                val argb = when (band) {
                    0 -> {
                        // 左半：4 档灰阶（10%..100%）；右半：黑→白平滑渐变
                        if (x < n / 2) {
                            val step = (x / (n / 8f)).toInt().coerceIn(0, 3)
                            val v = (48 + step * 64).coerceAtMost(255)
                            rgb(v, v, v)
                        } else {
                            val t = (x - n / 2f) / (n / 2f)
                            val v = (t * 255f).toInt().coerceIn(0, 255)
                            rgb(v, v, v)
                        }
                    }
                    1 -> {
                        // 记忆色：肤色 / 天空 / 绿叶 / 暖光 —— 色偏最容易被这四色暴露
                        val q = (x * 4 / n).coerceIn(0, 3)
                        when (q) {
                            0 -> rgb(232, 190, 166)   // 肤色
                            1 -> rgb(110, 170, 235)   // 天空
                            2 -> rgb(96, 148, 84)     // 绿叶
                            else -> rgb(238, 196, 120) // 暖光
                        }
                    }
                    else -> {
                        // 色轮：红 → 黄 → 绿 → 青 → 蓝 → 品红
                        hsv(x * 360f / n)
                    }
                }
                px[y * n + x] = argb
            }
        }
        return px
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** 极简 HSV→RGB（只用于生成色轮，S=0.85 V=0.95） */
    private fun hsv(hue: Float): Int {
        val s = 0.85f
        val v = 0.95f
        val c = v * s
        val hh = (hue % 360f) / 60f
        val x = c * (1f - Math.abs(hh % 2f - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        return rgb(
            ((r1 + m) * 255f).toInt().coerceIn(0, 255),
            ((g1 + m) * 255f).toInt().coerceIn(0, 255),
            ((b1 + m) * 255f).toInt().coerceIn(0, 255),
        )
    }
}
