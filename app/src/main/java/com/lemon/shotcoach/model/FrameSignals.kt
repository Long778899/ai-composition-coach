package com.lemon.shotcoach.model

/**
 * 单次分析产出的**原始视觉事实**（未经评分、未经平滑）。
 *
 * 这是分析层与领域层之间唯一的契约 —— 分析层只负责填这个结构，
 * 评分与规则只读这个结构，双方互不感知实现细节。
 */
data class FrameSignals(
    /** 平均亮度 0..255 */
    val brightness: Float = 0f,
    /** 欠曝像素（灰度 <= 10）占比 0..1 */
    val clipLowRatio: Float = 0f,
    /** 过曝像素（灰度 >= 245）占比 0..1 */
    val clipHighRatio: Float = 0f,

    /** 主体（显著性质心），归一化。无明确主体时为 null */
    val subject: NormalizedPoint? = null,
    /** 显著性集中度 0..1，越低说明画面越"没有重点" */
    val saliencyConcentration: Float = 0f,

    /** 设备绕 Z 轴倾角（度）。正 = 顺时针 */
    val rollDegrees: Float = 0f,
    /** 视觉地平线倾角（度）。null = 本帧未计算或不可信 */
    val horizonDegrees: Float? = null,

    /** 人脸框（归一化），按面积降序 */
    val faces: List<NormalizedRect> = emptyList(),

    /** 清晰度指标：Laplacian 方差，越大越清晰 */
    val sharpness: Float = 0f,

    val timestampMs: Long = 0L,
) {
    /** 最大人脸（若有） */
    val primaryFace: NormalizedRect? get() = faces.firstOrNull()

    /**
     * 融合后的水平倾角：视觉可信时优先视觉，否则用传感器。
     * 见 docs/ALGORITHMS.md §3.3
     */
    val effectiveRoll: Float
        get() {
            val h = horizonDegrees ?: return rollDegrees
            return if (kotlin.math.abs(h - rollDegrees) < 1.5f) rollDegrees else h
        }
}
