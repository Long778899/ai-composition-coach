package com.lemon.shotcoach.camera

/**
 * 抽帧（FR-1）。见 docs/ALGORITHMS.md §1、docs/ARCHITECTURE.md §2。
 *
 * 相机预览 30fps，但分析只需 6~10Hz —— 人眼对提示更新的感知已足够"实时"。
 *
 * 关键设计：
 *  - 每 [everyN] 帧取 1 帧；
 *  - 与 CameraX 的 STRATEGY_KEEP_ONLY_LATEST 配合：上一帧没分析完时新帧直接丢弃，
 *    **永不积压**（这是保证延迟不累积的关键）。
 *
 * 非线程安全：由分析线程单线程持有。
 */
class FrameThrottler(private val everyN: Int = 3) {

    init {
        require(everyN >= 1) { "everyN 必须 >= 1" }
    }

    private var counter = 0
    private var analyzing = false

    /** 计数器（用于各分析器的 cadence 判断） */
    val frameIndex: Int get() = counter

    /**
     * @return true 表示本帧应当分析
     */
    fun shouldAnalyze(): Boolean {
        // 上一帧仍在分析中 → 直接丢弃（配合 KEEP_ONLY_LATEST 双保险）
        if (analyzing) return false
        val hit = counter % everyN == 0
        counter++
        return hit
    }

    fun beginAnalysis() {
        analyzing = true
    }

    fun endAnalysis() {
        analyzing = false
    }

    /** 判断某个带 cadence 的分析器本帧是否该跑 */
    fun dueFor(cadence: Int): Boolean = cadence <= 1 || frameIndex % cadence == 0

    fun reset() {
        counter = 0
        analyzing = false
    }
}
