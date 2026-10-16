package com.lemon.shotcoach.model

import com.lemon.shotcoach.camera.GridStyle

/**
 * UI 状态（由分析管线通过 StateFlow 推送）。
 * UI 层只读这个结构，不含任何视觉算法。
 *
 * == 网格样式的唯一来源 ==
 * 这里刻意**复用** [com.lemon.shotcoach.camera.GridStyle]，而不是在自己内部
 * 再定义一个枚举。之前两处各有一个 GridStyle（一个 3 档、一个 5 档），
 * 参数页能选"对角线"但叠加层画不出来 —— 单一数据源从根上消除这类不一致。
 *
 * == 开关语义 ==
 *  - [gridEnabled]：网格线（对应参数的 GridStyle.OFF）
 *  - [levelEnabled]：水平基准线
 *  - [guideEnabled]：构图引导（人脸框 / 主体圈 / 方向箭头 / 提示文案）
 *  - [scoreEnabled]：评分 HUD（默认关，属调试/进阶信息）
 */
data class UiState(
    val hint: Hint? = null,
    val score: CompositionScore? = null,
    /** 平滑后的主体位置 */
    val subjectPoint: NormalizedPoint? = null,
    /** 端侧 AI 主体检测框（平滑后）。null = 未启用 AI，或本帧未检出主体 */
    val subjectBox: NormalizedRect? = null,
    /** 端侧 AI 主体类别标签（COCO 名称，如 "person"）。空串 = 无标签 */
    val subjectLabel: String = "",
    /** 本帧确实跑了神经网络的通道（UI 角标/诊断用，见 [AiChannel]） */
    val aiChannels: Set<AiChannel> = emptySet(),
    /**
     * 视觉地平线高度，归一化 0(画面顶)..1(画面底)。null = 本帧未检出可信地平线。
     *
     * v1.1.1 新增。存在两个用途：
     *  1. 引导模式下把**测到的地平线**画出来 —— 拍风景时肉眼很难判断它到底压在
     *     几分之几，画出来比说"往上抬一点"有效得多；
     *  2. 与 [Hint.Direction] 配合，让"地平线往上抬/往下压"这句话有据可依。
     *
     * 注意与 [rollDegrees] 的区别：那个是**倾斜**（用来校平），这个是**高度**（用来构图）。
     */
    val horizonY: Float? = null,
    /** 平滑后的水平倾角 */
    val rollDegrees: Float = 0f,
    val faceRects: List<NormalizedRect> = emptyList(),
    /** 实测分析帧率 */
    val fps: Float = 0f,
    /** 实测端到端延迟（毫秒） */
    val latencyMs: Float = 0f,
    val gridStyle: GridStyle = GridStyle.THIRDS,
    val gridEnabled: Boolean = true,
    val levelEnabled: Boolean = true,
    val guideEnabled: Boolean = true,
    val scoreEnabled: Boolean = false,
)

/**
 * 叠加层的显示配置（网格样式与各开关）。
 *
 * 单独抽出来的原因：这套配置是**纯 UI 关注点**，与分析结果无关，
 * 但必须跟每一帧的分析结果一起推给界面（否则切换开关要等下一帧才生效）。
 * 见 [com.lemon.shotcoach.analysis.FrameAnalyzer.overlayConfig]。
 */
data class OverlayConfig(
    val gridStyle: GridStyle = GridStyle.THIRDS,
    val gridEnabled: Boolean = true,
    val levelEnabled: Boolean = true,
    val guideEnabled: Boolean = true,
    val scoreEnabled: Boolean = false,
)
