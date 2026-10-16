package com.lemon.shotcoach.filter

/**
 * 一个 N×N×N 的 3D 色彩查找表（LUT）。
 *
 * == 内存布局 ==
 * `data` 长度 = N³×3，**红轴变化最快**：
 * ```
 * idx = (r + g*N + b*N*N) * 3     // 与 .cube 文件格式约定一致
 * ```
 * 选这个顺序是为了能直接导出成 Adobe `.cube`（见 [LutExporter]）。
 *
 * == 取值约定 ==
 * 输入/输出都归一化到 0..1（不是 0..255），避免调用方反复换算。
 */
class Lut3D(val size: Int, val data: FloatArray) {

    init {
        require(size >= 2) { "LUT 尺寸至少为 2，实际 $size" }
        require(data.size == size * size * size * 3) {
            "LUT 数据长度应为 ${size * size * size * 3}，实际 ${data.size}"
        }
    }

    /** 原始索引，不做边界检查（内部热点路径用） */
    fun rawIndex(r: Int, g: Int, b: Int): Int = (r + g * size + b * size * size) * 3

    /**
     * 三线性插值取值。用于**导出成片**（质量优先）。
     * @param r/g/b 0..1
     * @param out 长度≥3 的输出缓冲，避免每像素分配
     */
    fun sampleTrilinear(r: Float, g: Float, b: Float, out: FloatArray) {
        val rf = r.coerceIn(0f, 1f) * (size - 1)
        val gf = g.coerceIn(0f, 1f) * (size - 1)
        val bf = b.coerceIn(0f, 1f) * (size - 1)

        val r0 = rf.toInt(); val g0 = gf.toInt(); val b0 = bf.toInt()
        val r1 = if (r0 + 1 < size) r0 + 1 else r0
        val g1 = if (g0 + 1 < size) g0 + 1 else g0
        val b1 = if (b0 + 1 < size) b0 + 1 else b0

        val dr = rf - r0; val dg = gf - g0; val db = bf - b0

        for (c in 0..2) {
            val c000 = data[rawIndex(r0, g0, b0) + c]
            val c100 = data[rawIndex(r1, g0, b0) + c]
            val c010 = data[rawIndex(r0, g1, b0) + c]
            val c110 = data[rawIndex(r1, g1, b0) + c]
            val c001 = data[rawIndex(r0, g0, b1) + c]
            val c101 = data[rawIndex(r1, g0, b1) + c]
            val c011 = data[rawIndex(r0, g1, b1) + c]
            val c111 = data[rawIndex(r1, g1, b1) + c]

            val c00 = c000 + (c100 - c000) * dr
            val c10 = c010 + (c110 - c010) * dr
            val c01 = c001 + (c101 - c001) * dr
            val c11 = c011 + (c111 - c011) * dr

            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg

            out[c] = (c0 + (c1 - c0) * db).coerceIn(0f, 1f)
        }
    }

    /**
     * 构建"快查表"：把 LUT 重采样成 32×32×32 的打包 RGB int 表。
     *
     * 用于**实时预览**：每像素只需 3 次移位 + 1 次数组读取
     * （索引 = (r>>3) | (g>>3)<<5 | (b>>3)<<10），无需插值。
     * 5 bit/通道的量化误差在手机屏上不可见，但速度快一个数量级。
     */
    fun buildFastTable(): IntArray {
        val bits = 5
        val n = 1 shl bits          // 32
        val mask = n - 1
        val table = IntArray(n * n * n)
        val tmp = FloatArray(3)
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    sampleTrilinear(
                        r.toFloat() / mask,
                        g.toFloat() / mask,
                        b.toFloat() / mask,
                        tmp,
                    )
                    table[r or (g shl bits) or (b shl (bits * 2))] = packRgb(tmp)
                }
            }
        }
        return table
    }

    companion object {
        /** 单位 LUT（恒等变换），强度 0 时用 */
        fun identity(size: Int): Lut3D {
            val d = FloatArray(size * size * size * 3)
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        val i = (r + g * size + b * size * size) * 3
                        d[i] = r.toFloat() / (size - 1)
                        d[i + 1] = g.toFloat() / (size - 1)
                        d[i + 2] = b.toFloat() / (size - 1)
                    }
                }
            }
            return Lut3D(size, d)
        }

        fun packRgb(v: FloatArray): Int {
            val r = (v[0] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (v[1] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (v[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }
    }
}
