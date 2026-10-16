package com.lemon.shotcoach.filter

/**
 * 在 ARGB_8888 像素数组上应用 LUT。纯 Kotlin，无 Android 依赖 → 可单测。
 *
 * == 两条路径，各司其职 ==
 *  - [applyFast]：**实时预览**用。LUT 已重采样成 32³ 打包表，每像素
 *    3 次移位 + 1 次数组读取，无浮点运算。640×480 帧约 3~6ms。
 *  - [applyTrilinear]：**导出成片**用。真·三线性插值，12MP 约 1~2s，
 *    跑在后台线程（见 PhotoProcessor）。
 *
 * 两条路径都保持 alpha 不变 —— 滤镜只改颜色，不该动透明度。
 */
object CpuLutApplier {

    private const val BITS = 5
    private const val MASK = (1 shl BITS) - 1    // 31

    /**
     * 快查表路径（就地修改 [pixels]）。
     * @param table 由 [Lut3D.buildFastTable] 生成，长度 32³
     */
    fun applyFast(pixels: IntArray, table: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val key = (r shr (8 - BITS) and MASK) or
                ((g shr (8 - BITS) and MASK) shl BITS) or
                ((b shr (8 - BITS) and MASK) shl (BITS * 2))
            val rgb = table[key]
            pixels[i] = (p and 0xFF000000.toInt()) or rgb
        }
    }

    /**
     * 三线性路径（就地修改 [pixels]）。质量优先。
     */
    fun applyTrilinear(pixels: IntArray, lut: Lut3D) {
        val out = FloatArray(3)
        val inv = 1f / 255f
        for (i in pixels.indices) {
            val p = pixels[i]
            lut.sampleTrilinear(
                ((p shr 16) and 0xFF) * inv,
                ((p shr 8) and 0xFF) * inv,
                (p and 0xFF) * inv,
                out,
            )
            pixels[i] = (p and 0xFF000000.toInt()) or Lut3D.packRgb(out)
        }
    }

    /**
     * 强度插值版本（快查表路径）：在原图与此滤镜之间按 [strength] 混合。
     *
     * 为什么不在预览时重烘焙 LUT：烘焙一次 33³ 约 10~20ms，滑杆拖动时
     * 每帧烘焙会卡；直接对**结果**插值只需一次整数 lerp，零烘焙成本。
     * 数学上与烘焙时插值不完全等价（后者在线性光下更准），但视觉差异
     * 在 5bit 量化的预览上不可见。
     */
    fun applyFastBlended(pixels: IntArray, table: IntArray, strength: Float) {
        val t = (strength.coerceIn(0f, 1f) * 256f).toInt()
        if (t >= 256) {
            applyFast(pixels, table)
            return
        }
        if (t <= 0) return
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val key = (r shr (8 - BITS) and MASK) or
                ((g shr (8 - BITS) and MASK) shl BITS) or
                ((b shr (8 - BITS) and MASK) shl (BITS * 2))
            val rgb = table[key]
            val fr = 255 - t
            val nr = (r * fr + ((rgb shr 16) and 0xFF) * t) shr 8
            val ng = (g * fr + ((rgb shr 8) and 0xFF) * t) shr 8
            val nb = (b * fr + (rgb and 0xFF) * t) shr 8
            pixels[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }
}
