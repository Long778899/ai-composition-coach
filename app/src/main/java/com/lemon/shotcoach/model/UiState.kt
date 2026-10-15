package com.lemon.shotcoach.model

/**
 * UI 状态（由分析管线通过 StateFlow 推送）。
 * UI 层只读这个结构，不含任何视觉算法。
 */
data class UiState(
    val hint: Hint? = null,
    val score: CompositionScore? = null,
    /** 平滑后的主体位置 */
    val subjectPoint: NormalizedPoint? = null,
    /** 平滑后的水平倾角 */
    val rollDegrees: Float = 0f,
    val faceRects: List<NormalizedRect> = emptyList(),
    /** 实测分析帧率 */
    val fps: Float = 0f,
    /** 实测端到端延迟（毫秒） */
    val latencyMs: Float = 0f,
    val gridStyle: GridStyle = GridStyle.THIRDS,
) {
    enum class GridStyle { THIRDS, GOLDEN, CROSS }
}
