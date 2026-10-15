package com.lemon.shotcoach.analysis

import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.NormalizedPoint

/**
 * 时间平滑（FR-10）。抑制提示抖动，满足 G3。
 *
 * 对连续量（主体坐标、roll、亮度）做指数移动平均：
 *     s_t = α·x_t + (1-α)·s_{t-1}
 *
 * α 越大越灵敏但越抖；越小越稳但越迟钝。
 * 0.35 在 ~6Hz 分析下有约 0.5s 时间常数，手感最佳（见 docs/ALGORITHMS.md §8.1）。
 *
 * 非线程安全：由分析线程单线程持有。
 */
class EmaSmoother(private val alpha: Float = 0.35f) {

    private var initialized = false

    private var smRoll = 0f
    private var smBrightness = 0f
    private var smSubject: NormalizedPoint? = null
    private var smSharpness = 0f

    fun smooth(signals: FrameSignals): FrameSignals {
        val roll = ema(smRoll, signals.effectiveRoll).also { smRoll = it }
        val brightness = ema(smBrightness, signals.brightness).also { smBrightness = it }
        val sharpness = ema(smSharpness, signals.sharpness).also { smSharpness = it }

        val subject = smoothPoint(signals.subject)

        initialized = true

        // 注意：clip 比例与人脸框不需要平滑（本身就是统计量），保留原值
        return signals.copy(
            brightness = brightness,
            sharpness = sharpness,
            subject = subject,
            rollDegrees = roll,
        )
    }

    private fun ema(prev: Float, x: Float): Float {
        if (!initialized) return x   // 首帧直接取 x，避免从 0 缓慢爬升
        return alpha * x + (1f - alpha) * prev
    }

    private fun smoothPoint(p: NormalizedPoint?): NormalizedPoint? {
        if (p == null) {
            smSubject = null
            return null
        }
        val prev = smSubject
        val next = if (prev == null) p else NormalizedPoint(
            x = alpha * p.x + (1f - alpha) * prev.x,
            y = alpha * p.y + (1f - alpha) * prev.y,
        )
        smSubject = next
        return next
    }

    fun reset() {
        initialized = false
        smRoll = 0f
        smBrightness = 0f
        smSubject = null
        smSharpness = 0f
    }
}
