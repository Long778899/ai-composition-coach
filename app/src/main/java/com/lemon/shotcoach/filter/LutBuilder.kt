package com.lemon.shotcoach.filter

import com.lemon.shotcoach.camera.FilterSpec
import kotlin.math.pow

/**
 * 把 [FilterSpec] 的**色彩参数**烘焙成一张 3D LUT。
 *
 * 整个类**纯 Kotlin**（不依赖 Android），因此可以：
 *  - JVM 单测（验证"中性参数 = 单位 LUT"、"黑白滤镜 R=G=B"等数学性质）；
 *  - 直接导出 .cube 到桌面端调色软件复用。
 *
 * == 处理顺序（顺序会影响结果，不是随便排的）==
 * 1. 黑白化（最彻底，先做）
 * 2. 单色分离（黑白后的冷暖倾向）
 * 3. 饱和度 / 自然饱和度
 * 4. 色温 / 色调（白平衡偏移）
 * 5. 分离色调（暗部/高光分别染色）
 * 6. 对比度
 * 7. 亮度 / 褪色（提黑位）
 * 8. 按 [strength] 与原图插值
 *
 * == 为什么用 sRGB 空间而不是线性光 ==
 * 这是**风格化滤镜**不是物理正确的色彩管理。在 sRGB 空间做对比度/褪色更接近
 * 传统照片调色的直观效果（也是绝大多数相机 App 滤镜的做法），
 * 换成线性空间会导致对比度曲线手感完全不同。
 */
object LutBuilder {

    /** 默认 LUT 尺寸。33 是行业惯例（.cube 常见 33³，质量与体积平衡点） */
    const val DEFAULT_SIZE = 33

    /**
     * @param strength 强度 0..1；0 → 单位 LUT（原图），1 → 完整滤镜
     */
    fun bake(spec: FilterSpec, strength: Float = 1f, size: Int = DEFAULT_SIZE): Lut3D {
        val s = strength.coerceIn(0f, 1f)
        val identity = Lut3D.identity(size)
        if (s == 0f || spec.isIdentity) return identity

        val out = FloatArray(size * size * size * 3)
        val max = (size - 1).toFloat()

        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val i = (r + g * size + b * size * size) * 3
                    var cr = r / max
                    var cg = g / max
                    var cb = b / max

                    // 1. 黑白
                    if (spec.monochrome) {
                        val luma = luma(cr, cg, cb)
                        cr = luma; cg = luma; cb = luma
                        // 2. 单色分离
                        if (spec.monoTone != 0f) {
                            val t = spec.monoTone
                            cr = (cr + 0.06f * t * luma).coerceIn(0f, 1f)
                            cg = (cg + 0.01f * t * luma).coerceIn(0f, 1f)
                            cb = (cb - 0.07f * t * (1f - luma) - 0.02f * t * luma).coerceIn(0f, 1f)
                        }
                    } else {
                        // 3. 饱和度
                        if (spec.saturation != 1f) {
                            val l = luma(cr, cg, cb)
                            cr = l + (cr - l) * spec.saturation
                            cg = l + (cg - l) * spec.saturation
                            cb = l + (cb - l) * spec.saturation
                        }
                        // 3b. 自然饱和度：只提不饱和的区域，保护已有的鲜艳色
                        if (spec.vibrance > 0f) {
                            val mx = maxOf(cr, cg, cb)
                            val mn = minOf(cr, cg, cb)
                            val sat = mx - mn
                            val boost = spec.vibrance * (1f - sat) * 0.6f
                            val l = luma(cr, cg, cb)
                            cr = l + (cr - l) * (1f + boost)
                            cg = l + (cg - l) * (1f + boost)
                            cb = l + (cb - l) * (1f + boost)
                        }
                    }

                    // 4. 色温 / 色调
                    if (spec.temperature != 0f) {
                        cr = (cr * (1f + 0.28f * spec.temperature)).coerceIn(0f, 1f)
                        cb = (cb * (1f - 0.28f * spec.temperature)).coerceIn(0f, 1f)
                    }
                    if (spec.tint != 0f) {
                        cg = (cg * (1f - 0.16f * spec.tint)).coerceIn(0f, 1f)
                        cr = (cr * (1f + 0.05f * spec.tint)).coerceIn(0f, 1f)
                        cb = (cb * (1f + 0.05f * spec.tint)).coerceIn(0f, 1f)
                    }

                    // 5. 分离色调
                    if (spec.splitStrength > 0f) {
                        val l = luma(cr, cg, cb)
                        // 暗部权重（越暗越强）与高光权重
                        val wShadow = (1f - l).pow(2f) * spec.splitStrength
                        val wHigh = l.pow(2f) * spec.splitStrength
                        // 负 = 偏冷青，正 = 偏暖黄
                        cr = (cr + 0.10f * (spec.shadowTint * wShadow + spec.highlightTint * wHigh)).coerceIn(0f, 1f)
                        cg = (cg + 0.02f * (spec.shadowTint * wShadow + spec.highlightTint * wHigh)).coerceIn(0f, 1f)
                        cb = (cb - 0.10f * (spec.shadowTint * wShadow + spec.highlightTint * wHigh)).coerceIn(0f, 1f)
                    }

                    // 6. 对比度（围绕中灰 0.5）
                    if (spec.contrast != 1f) {
                        cr = (cr - 0.5f) * spec.contrast + 0.5f
                        cg = (cg - 0.5f) * spec.contrast + 0.5f
                        cb = (cb - 0.5f) * spec.contrast + 0.5f
                    }

                    // 7. 亮度 + 褪色（提黑位 = 胶片感）
                    if (spec.brightness != 0f) {
                        cr += spec.brightness; cg += spec.brightness; cb += spec.brightness
                    }
                    if (spec.fade > 0f) {
                        val lift = spec.fade * 0.22f
                        cr = lift + cr * (1f - lift)
                        cg = lift + cg * (1f - lift)
                        cb = lift + cb * (1f - lift)
                        // 同时轻微压低白位，避免整体发灰发亮
                        cr *= (1f - spec.fade * 0.06f)
                        cg *= (1f - spec.fade * 0.06f)
                        cb *= (1f - spec.fade * 0.06f)
                    }

                    // 8. 强度插值（与单位 LUT 混合）
                    out[i] = mix(identity.data[i], cr.coerceIn(0f, 1f), s)
                    out[i + 1] = mix(identity.data[i + 1], cg.coerceIn(0f, 1f), s)
                    out[i + 2] = mix(identity.data[i + 2], cb.coerceIn(0f, 1f), s)
                }
            }
        }
        return Lut3D(size, out)
    }

    /** 亮度（Rec.709 系数，与 sRGB 观感一致） */
    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
}
