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

    // ── 端侧 AI 通道（路径 1：真神经网络）─────────────────────────
    // 全部带默认值：AI 未启用时本结构逐位等同于旧版，评分/规则/UI 行为不变。
    // 见 docs/ALGORITHMS.md §10。

    /** 端侧 AI 主体检测框（归一化）。null = 未启用 AI，或本帧未检出主体 */
    val subjectBox: NormalizedRect? = null,

    /** 主体类别标签（COCO 80 类，如 "person" / "dog"）。空串 = 无标签 */
    val subjectLabel: String = "",

    /** 主体置信度 0..1 */
    val subjectConfidence: Float = 0f,

    /** 人脸框来自神经网络（true）还是传统 Haar 级联（false） */
    val facesFromAi: Boolean = false,

    /** 端侧 AI 美感分 1..10（NIMA aesthetic）。null = 未启用 */
    val aestheticScore: Float? = null,

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

    /** 端侧 AI 是否给出了主体框 */
    val hasAiSubject: Boolean get() = subjectBox != null

    /**
     * 主体位置：**优先端侧 AI 的检测框中心**，没有 AI 时退回显著性质心。
     *
     * 这是接入 AI 的关键收敛点：评分器、`SubjectOffRule`、`FaceNoneRule`、UI 全部读这一个字段。
     * 一处切换，全局从"猜"变成"测"——而 AI 关闭时它的返回值与旧的 [subject] 完全相同。
     */
    val effectiveSubject: NormalizedPoint? get() = subjectBox?.center ?: subject

    /** 本帧实际走了神经网络的通道（UI 角标 / 诊断） */
    val aiChannels: Set<AiChannel>
        get() = buildSet {
            if (hasAiSubject) add(AiChannel.SUBJECT)
            if (aestheticScore != null) add(AiChannel.AESTHETIC)
            if (facesFromAi && faces.isNotEmpty()) add(AiChannel.FACE)
        }
}

/**
 * 端侧 AI 通道标识：用来区分"这一路本帧真的跑了神经网络"还是"走了传统 CV 回退"。
 *
 * 存在的意义是**可观测**：调试时一眼看出 AI 是不是真在工作，
 * 而不是像 v1.0 那样"界面写着 AI 构图教练，其实一行模型代码都没跑"。
 */
enum class AiChannel {
    /** 主体检测（EfficientDet-Lite0） */
    SUBJECT,

    /** 人脸检测（BlazeFace） */
    FACE,

    /** 美感评分（NIMA） */
    AESTHETIC,
}
