package com.lemon.shotcoach.camera

import com.lemon.shotcoach.render.BorderCatalog

/**
 * 相机参数模型（整机唯一的"可调项"数据源）。
 *
 * == 设计要点 ==
 * 1. **不可变 data class**：每次调整 copy 出新实例 → 便于做撤销/预设、便于 diff 决定下发哪些硬件参数。
 * 2. **auto 用哨兵值表示**：ISO=0 / 快门=0L / EV 用 index → 避免额外 nullable 分支。
 * 3. **零 Android 依赖**：整个文件是纯 Kotlin，可直接 JVM 单测（见 ParamModelTest）。
 *    与硬件枚举的映射集中在 [CameraParamController]，让模型与平台解耦。
 */
data class CameraParams(
    /** 曝光补偿，以 0.5EV 为步进的 index（见 [EvRange]）。0 = 0EV */
    val evIndex: Int = 0,

    /** ISO 感光度。0 = 自动；否则 [IsoRange.MIN]..[IsoRange.MAX] */
    val iso: Int = ISO_AUTO,

    /** 快门时间（纳秒）。0 = 自动；否则 [ShutterRange.MIN_NS]..[ShutterRange.MAX_NS] */
    val shutterNs: Long = SHUTTER_AUTO,

    /** 白平衡预设；[WhiteBalancePreset.CUSTOM] 时用 [wbTemperatureK] */
    val wbPreset: WhiteBalancePreset = WhiteBalancePreset.AUTO,
    /** 自定义色温 2000K..8000K */
    val wbTemperatureK: Int = 5500,

    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    /** 手动对焦（屈光度 0..10，0=无穷远，10≈10cm）。仅 [FocusMode.MANUAL] 生效 */
    val manualFocusDiopters: Float = 0f,

    /** 变焦倍率。由当前镜头的 [ZoomRange] 约束 */
    val zoomRatio: Float = 1f,

    val flash: FlashMode = FlashMode.OFF,

    /** 构图网格 */
    val grid: GridStyle = GridStyle.THIRDS,
    /** 画幅比例 */
    val aspect: AspectRatio = AspectRatio.RATIO_4_3,

    /** 水平仪叠加 */
    val levelEnabled: Boolean = true,
    /** 构图引导提示（本项目核心能力） */
    val guideEnabled: Boolean = true,

    /** 定时拍摄 */
    val timer: TimerOption = TimerOption.OFF,

    /** 前置镜像 */
    val mirrorFront: MirrorMode = MirrorMode.AUTO,

    /** 当前滤镜 ID，见 [FilterCatalog] */
    val filterId: String = FilterCatalog.NONE_ID,

    /** 滤镜强度 0..1。[FilterCatalog.NONE_ID] 时无意义 */
    val filterStrength: Float = 1f,

    val watermark: WatermarkConfig = WatermarkConfig(),

    /** 边框 ID，见 [BorderCatalog] */
    val borderId: String = BorderCatalog.NONE_ID,

    val shutterSound: Boolean = true,
    val saveFormat: SaveFormat = SaveFormat.JPEG,
    /** JPEG 质量 60..100 */
    val quality: Int = 95,

    /** [androidx.camera.core.CameraSelector.LENS_FACING_BACK] / FRONT */
    val lensFacing: Int = LENS_FACING_BACK,
) {
    // ── 派生值 ────────────────────────────────────────────────

    /** 曝光补偿的 EV 值，如 index=-2 → -1.0EV */
    val ev: Float get() = evIndex * EV_STEP

    val isIsoAuto: Boolean get() = iso == ISO_AUTO
    val isShutterAuto: Boolean get() = shutterNs == SHUTTER_AUTO
    val isExposureFullyAuto: Boolean get() = isIsoAuto && isShutterAuto

    /** 快门的人类可读表示，如 "1/500" 或 "2.5"" */
    val shutterLabel: String
        get() = if (isShutterAuto) "自动" else formatShutter(shutterNs)

    val isoLabel: String get() = if (isIsoAuto) "自动" else iso.toString()

    val zoomLabel: String get() = formatZoom(zoomRatio)

    /** 面板上"专业模式"是否有任何非自动项 */
    val isProModified: Boolean
        get() = !isExposureFullyAuto || wbPreset != WhiteBalancePreset.AUTO ||
            focusMode != FocusMode.CONTINUOUS || evIndex != 0

    companion object {
        const val ISO_AUTO = 0
        const val SHUTTER_AUTO = 0L
        const val EV_STEP = 0.5f
        const val LENS_FACING_BACK = 0     // 对齐 CameraSelector.LENS_FACING_BACK
        const val LENS_FACING_FRONT = 1    // 对齐 CameraSelector.LENS_FACING_FRONT

        /** 把纳秒格式化成 "1/500" / "2.5\"" 这样的摄影习惯写法 */
        fun formatShutter(ns: Long): String {
            if (ns <= 0L) return "自动"
            val seconds = ns / 1_000_000_000.0
            return if (seconds >= 1.0) {
                val s = (seconds * 10).toInt() / 10.0
                "$s\""
            } else {
                "1/${Math.round(1.0 / seconds)}"
            }
        }

        fun formatZoom(ratio: Float): String {
            val rounded = Math.round(ratio * 10f) / 10f
            return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}x" else "${rounded}x"
        }

        /**
         * 自动模式下根据环境亮度推荐一组 ISO/快门（"专业模式"的一键建议）。
         * 纯函数 → 可单测。
         */
        fun suggestExposure(brightness: Float): Pair<Int, Long> {
            // 亮度 0..255 线性映射到 4 档
            val iso = when {
                brightness < 60f -> 800
                brightness < 110f -> 400
                brightness < 170f -> 200
                else -> 100
            }
            val shutter = when {
                brightness < 60f -> 30_000_000L        // 1/33
                brightness < 110f -> 15_000_000L       // 1/67
                brightness < 170f -> 8_000_000L        // 1/125
                else -> 4_000_000L                     // 1/250
            }
            return iso to shutter
        }
    }
}

// ── 取值范围 ────────────────────────────────────────────────

object EvRange {
    const val MIN_INDEX = -6   // -3.0 EV
    const val MAX_INDEX = 6    // +3.0 EV
    val STEPS = (MIN_INDEX..MAX_INDEX).toList()
}

object IsoRange {
    const val MIN = 50
    const val MAX = 3200
    /** 常见档位（滑动条吸附用） */
    val STOPS = listOf(50, 100, 200, 400, 800, 1600, 3200)

    /** 找最接近的档位 */
    fun snap(value: Int): Int = STOPS.minByOrNull { Math.abs(it - value) } ?: 100
}

object ShutterRange {
    /** 1/2000s */
    const val MIN_NS = 500_000L
    /** 1s */
    const val MAX_NS = 1_000_000_000L
    /** 常见档位 */
    val STOPS = listOf(
        500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
        15_000_000L, 30_000_000L, 60_000_000L, 125_000_000L,
        250_000_000L, 500_000_000L, 1_000_000_000L,
    )

    fun snap(ns: Long): Long = STOPS.minByOrNull { Math.abs(it - ns) } ?: 8_000_000L
}

object TempRange {
    const val MIN_K = 2000
    const val MAX_K = 8000
    const val STEP_K = 100
    val STOPS = (MIN_K..MAX_K step STEP_K).toList()
}

object ZoomRange {
    const val MIN = 0.5f
    const val MAX = 8f
    /** 快捷倍率 */
    val QUICK = listOf(0.5f, 1f, 2f, 3f, 5f)
}

// ── 枚举 ────────────────────────────────────────────────────

enum class WhiteBalancePreset(val label: String) {
    AUTO("自动"),
    DAYLIGHT("日光"),
    CLOUDY("阴天"),
    SHADE("阴影"),
    INCANDESCENT("白炽灯"),
    FLUORESCENT("荧光灯"),
    CUSTOM("色温"),
}

enum class FocusMode(val label: String) {
    CONTINUOUS("连续 AF"),
    SINGLE("单次 AF"),
    MANUAL("手动 MF"),
}

enum class FlashMode(val label: String) {
    OFF("关"),
    AUTO("自动"),
    ON("开"),
    TORCH("常亮"),
}

enum class GridStyle(val label: String) {
    OFF("无"),
    THIRDS("三分法"),
    GOLDEN("黄金分割"),
    CENTER("中心十字"),
    DIAGONAL("对角线"),
}

enum class AspectRatio(val ratio: Float, val label: String) {
    RATIO_4_3(4f / 3f, "4:3"),
    RATIO_16_9(16f / 9f, "16:9"),
    RATIO_1_1(1f, "1:1"),
    RATIO_3_4(3f / 4f, "3:4"),
}

enum class TimerOption(val seconds: Int, val label: String) {
    OFF(0, "关"),
    S3(3, "3s"),
    S10(10, "10s"),
}

enum class MirrorMode(val label: String) {
    AUTO("自动"),
    ON("开"),
    OFF("关"),
}

enum class SaveFormat(val label: String, val mime: String, val ext: String) {
    JPEG("JPEG", "image/jpeg", "jpg"),
    PNG("PNG（无损）", "image/png", "png"),
    HEIC("HEIC（省空间）", "image/heic", "heic"),
}

// ── 水印 ────────────────────────────────────────────────────

data class WatermarkConfig(
    val enabled: Boolean = false,
    /** 右下角时间戳 */
    val showTime: Boolean = true,
    /** 左下角机型标识 */
    val showDevice: Boolean = false,
    /** 自定义文字（可为空） */
    val customText: String = "",
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_LEFT,
    /** 0f..1f */
    val opacity: Float = 0.7f,
    /** ARGB */
    val color: Int = 0xFFFFFFFF.toInt(),
    /** 相对短边的字号比例 */
    val fontSizeRatio: Float = 0.030f,
)

enum class WatermarkPosition(val label: String) {
    TOP_LEFT("左上"),
    TOP_RIGHT("右上"),
    BOTTOM_LEFT("左下"),
    BOTTOM_RIGHT("右下"),
}
