package com.lemon.shotcoach.filter

import kotlin.math.ln
import kotlin.math.pow

/**
 * 色温 → RGB 的换算（纯 Kotlin，可单测）。
 *
 * 用 Tanner Helland 的黑体辐射近似公式把开尔文色温映射到 sRGB，
 * 再归一化成功率增益交给 `COLOR_CORRECTION_GAINS`。
 *
 * == 为什么需要它 ==
 * "色温"滑杆要真的改变画面。Android 的 `CONTROL_AWB_MODE` 只提供几个离散预设
 * （日光/阴天/白炽灯…），拿不到连续色温。要做出连续可调，必须自己算增益并
 * 关掉 AWB，改用 `COLOR_CORRECTION_GAINS` 手动下发。
 */
object ColorTemp {

    /** 参考色温：设备在此色温下不偏色（近似中性） */
    const val NEUTRAL_K = 5500

    /**
     * 开尔文 → 0..255 的 RGB。
     * 适用范围 1000K..40000K；本 App 只用到 2000..8000K。
     */
    fun kelvinToRgb(kelvin: Int): Triple<Int, Int, Int> {
        val t = kelvin.coerceIn(1000, 40000) / 100.0

        val r: Double = if (t <= 66) {
            255.0
        } else {
            (329.698727446 * (t - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        }

        val g: Double = if (t <= 66) {
            (99.4708025861 * ln(t) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }

        val b: Double = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> (138.5177312231 * ln(t - 10) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        return Triple(r.toInt(), g.toInt(), b.toInt())
    }

    /**
     * 计算 `COLOR_CORRECTION_GAINS` 的 (rGain, gGain, bGain)。
     *
     * 语义：**用户把色温调到 T，是希望画面呈现 T 的色调**（暖=偏黄，冷=偏蓝），
     * 所以增益直接在 T 的 RGB 上归一化 —— 与相机硬件的"补偿光源白点"语义相反，
     * 这里是刻意做成"风格化色温"（摄影 App 的习惯做法，也是用户滑滑杆时的预期）。
     *
     * g 通道归一到 1.0，避免整体亮度变化。
     */
    fun gainsFor(kelvin: Int): Triple<Float, Float, Float> {
        val (r, g, b) = kelvinToRgb(kelvin)
        val gf = g.coerceAtLeast(1).toFloat()
        // 适度压缩，避免极端色温把增益推到硬件不接受的范围
        val rGain = (r / gf).coerceIn(0.3f, 3.0f)
        val bGain = (b / gf).coerceIn(0.3f, 3.0f)
        return Triple(rGain, 1.0f, bGain)
    }

    /** 相对中性色温的偏移量，-1(极冷)..+1(极暖)，用于 UI 显示 */
    fun bias(kelvin: Int): Float =
        ((kelvin - NEUTRAL_K).toFloat() / (TempRangeSpan)).coerceIn(-1f, 1f)

    private const val TempRangeSpan = 2500f
}
