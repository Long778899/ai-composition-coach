package com.lemon.shotcoach.analysis

import com.lemon.shotcoach.model.FrameSignals
import com.lemon.shotcoach.model.NormalizedPoint
import com.lemon.shotcoach.model.NormalizedRect

/**
 * 时间平滑（FR-10）。抑制提示抖动，满足 G3。
 *
 * 对连续量（主体坐标、主体框、roll、亮度、美感分）做指数移动平均：
 *     s_t = α·x_t + (1-α)·s_{t-1}
 *
 * α 越大越灵敏但越抖；越小越稳但越迟钝。
 * 0.35 在 ~6Hz 分析下有约 0.5s 时间常数，手感最佳（见 docs/ALGORITHMS.md §8.1）。
 *
 * ## v1.1：AI 字段也要平滑
 *
 * 神经网络输出天然有帧间抖动（主体框会小幅跳），**不平滑反而比 CV 更容易看出抖**。
 * 所以 [FrameSignals.subjectBox] 四个角与 [FrameSignals.aestheticScore] 一并 EMA。
 *
 * 注意 `aestheticScore` 的特殊语义：`null` 表示"这一路没开"，不是"这一帧测得 0 分"，
 * 所以 `null` 输入时**保持上一次的值**而不是清零——否则抽帧（每 N 帧才算一次）会让分数闪断。
 *
 * 非线程安全：由分析线程单线程持有。
 */
class EmaSmoother(private val alpha: Float = 0.35f) {

    private var initialized = false

    private var smRoll = 0f
    private var smBrightness = 0f
    private var smSubject: NormalizedPoint? = null
    private var smSharpness = 0f
    private var smBox: NormalizedRect? = null
    private var smAesthetic: Float? = null

    fun smooth(signals: FrameSignals): FrameSignals {
        val roll = ema(smRoll, signals.effectiveRoll).also { smRoll = it }
        val brightness = ema(smBrightness, signals.brightness).also { smBrightness = it }
        val sharpness = ema(smSharpness, signals.sharpness).also { smSharpness = it }

        val subject = smoothPoint(signals.subject)
        val box = smoothRect(signals.subjectBox)
        val aesthetic = smoothOptional(smAesthetic, signals.aestheticScore).also { smAesthetic = it }

        initialized = true

        // 注意：clip 比例与人脸框不需要平滑（本身就是统计量），保留原值
        return signals.copy(
            brightness = brightness,
            sharpness = sharpness,
            subject = subject,
            subjectBox = box,
            aestheticScore = aesthetic,
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

    private fun smoothRect(r: NormalizedRect?): NormalizedRect? {
        if (r == null) {
            smBox = null
            return null
        }
        val prev = smBox
        val next = if (prev == null) r else NormalizedRect(
            left = alpha * r.left + (1f - alpha) * prev.left,
            top = alpha * r.top + (1f - alpha) * prev.top,
            right = alpha * r.right + (1f - alpha) * prev.right,
            bottom = alpha * r.bottom + (1f - alpha) * prev.bottom,
        )
        smBox = next
        return next
    }

    /**
     * 可空标量的 EMA：`null` 输入保持旧值（"这一路没开" ≠ "这一帧是 0"）。
     */
    private fun smoothOptional(prev: Float?, x: Float?): Float? {
        if (x == null) return prev
        if (prev == null) return x
        return alpha * x + (1f - alpha) * prev
    }

    fun reset() {
        initialized = false
        smRoll = 0f
        smBrightness = 0f
        smSubject = null
        smSharpness = 0f
        smBox = null
        smAesthetic = null
    }
}
