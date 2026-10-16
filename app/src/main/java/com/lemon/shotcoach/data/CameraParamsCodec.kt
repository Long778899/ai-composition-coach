package com.lemon.shotcoach.data

import com.lemon.shotcoach.camera.AspectRatio
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.camera.FilterCatalog
import com.lemon.shotcoach.camera.FlashMode
import com.lemon.shotcoach.camera.FocusMode
import com.lemon.shotcoach.camera.GridStyle
import com.lemon.shotcoach.camera.MirrorMode
import com.lemon.shotcoach.camera.SaveFormat
import com.lemon.shotcoach.camera.TimerOption
import com.lemon.shotcoach.camera.WatermarkConfig
import com.lemon.shotcoach.camera.WatermarkPosition
import com.lemon.shotcoach.camera.WhiteBalancePreset
import com.lemon.shotcoach.render.BorderCatalog

/**
 * 相机参数 ↔ 字符串字典 的编解码器。
 *
 * 为什么手写而不用 JSON 库：整个文件是**纯 Kotlin**，没有反射、没有注解处理器、
 * 没有额外依赖（APK 不会因此变大），并且可以直接 JVM 单测往返一致性。
 *
 * == 健壮性 ==
 * 解码遇到非法/缺失的值一律**回落到默认值**，绝不抛异常 ——
 * 用户升级 App 后旧存档或损坏存档不会导致启动崩溃（见 decode 的各处 fallback）。
 */
object CameraParamsCodec {

    // 键名集中定义，避免笔误
    private const val K_EV = "ev"
    private const val K_ISO = "iso"
    private const val K_SHUTTER = "shutter"
    private const val K_WB = "wb"
    private const val K_WB_K = "wbK"
    private const val K_FOCUS = "focus"
    private const val K_FOCUS_D = "focusD"
    private const val K_ZOOM = "zoom"
    private const val K_FLASH = "flash"
    private const val K_GRID = "grid"
    private const val K_ASPECT = "aspect"
    private const val K_LEVEL = "level"
    private const val K_GUIDE = "guide"
    private const val K_TIMER = "timer"
    private const val K_MIRROR = "mirror"
    private const val K_FILTER = "filter"
    private const val K_FILTER_S = "filterS"
    private const val K_BORDER = "border"
    private const val K_SOUND = "sound"
    private const val K_FORMAT = "format"
    private const val K_QUALITY = "quality"
    private const val K_LENS = "lens"

    private const val K_WM_ON = "wmOn"
    private const val K_WM_TIME = "wmTime"
    private const val K_WM_DEVICE = "wmDevice"
    private const val K_WM_TEXT = "wmText"
    private const val K_WM_POS = "wmPos"
    private const val K_WM_OPACITY = "wmOpacity"
    private const val K_WM_COLOR = "wmColor"
    private const val K_WM_SIZE = "wmSize"

    fun encode(p: CameraParams): Map<String, String> = mapOf(
        K_EV to p.evIndex.toString(),
        K_ISO to p.iso.toString(),
        K_SHUTTER to p.shutterNs.toString(),
        K_WB to p.wbPreset.name,
        K_WB_K to p.wbTemperatureK.toString(),
        K_FOCUS to p.focusMode.name,
        K_FOCUS_D to p.manualFocusDiopters.toString(),
        K_ZOOM to p.zoomRatio.toString(),
        K_FLASH to p.flash.name,
        K_GRID to p.grid.name,
        K_ASPECT to p.aspect.name,
        K_LEVEL to p.levelEnabled.toString(),
        K_GUIDE to p.guideEnabled.toString(),
        K_TIMER to p.timer.name,
        K_MIRROR to p.mirrorFront.name,
        K_FILTER to p.filterId,
        K_FILTER_S to p.filterStrength.toString(),
        K_BORDER to p.borderId,
        K_SOUND to p.shutterSound.toString(),
        K_FORMAT to p.saveFormat.name,
        K_QUALITY to p.quality.toString(),
        K_LENS to p.lensFacing.toString(),
        K_WM_ON to p.watermark.enabled.toString(),
        K_WM_TIME to p.watermark.showTime.toString(),
        K_WM_DEVICE to p.watermark.showDevice.toString(),
        K_WM_TEXT to p.watermark.customText,
        K_WM_POS to p.watermark.position.name,
        K_WM_OPACITY to p.watermark.opacity.toString(),
        K_WM_COLOR to p.watermark.color.toString(),
        K_WM_SIZE to p.watermark.fontSizeRatio.toString(),
    )

    /**
     * 解码。任何字段缺失或非法 → 用 [CameraParams] 的默认值。
     * 特别注意：[K_FILTER]/[K_BORDER] 会校验 ID 是否在目录中存在，
     * 防止旧版本存档里的已删除滤镜导致后续 NPE。
     */
    fun decode(map: Map<String, String>): CameraParams {
        val d = CameraParams()

        val filterId = map[K_FILTER].orEmpty()
        val borderId = map[K_BORDER].orEmpty()

        return CameraParams(
            evIndex = map[K_EV]?.toIntOrNull()?.coerceIn(-6, 6) ?: d.evIndex,
            iso = map[K_ISO]?.toIntOrNull()?.let { if (it == 0) 0 else it.coerceIn(50, 3200) } ?: d.iso,
            shutterNs = map[K_SHUTTER]?.toLongOrNull()
                ?.let { if (it == 0L) 0L else it.coerceIn(500_000L, 1_000_000_000L) } ?: d.shutterNs,
            wbPreset = enumOrDefault(map[K_WB], WhiteBalancePreset.AUTO),
            wbTemperatureK = map[K_WB_K]?.toIntOrNull()?.coerceIn(2000, 8000) ?: d.wbTemperatureK,
            focusMode = enumOrDefault(map[K_FOCUS], FocusMode.CONTINUOUS),
            manualFocusDiopters = map[K_FOCUS_D]?.toFloatOrNull()?.coerceIn(0f, 10f) ?: d.manualFocusDiopters,
            zoomRatio = map[K_ZOOM]?.toFloatOrNull()?.coerceIn(0.5f, 8f) ?: d.zoomRatio,
            flash = enumOrDefault(map[K_FLASH], FlashMode.OFF),
            grid = enumOrDefault(map[K_GRID], GridStyle.THIRDS),
            aspect = enumOrDefault(map[K_ASPECT], AspectRatio.RATIO_4_3),
            levelEnabled = map[K_LEVEL]?.toBooleanStrictOrNull() ?: d.levelEnabled,
            guideEnabled = map[K_GUIDE]?.toBooleanStrictOrNull() ?: d.guideEnabled,
            timer = enumOrDefault(map[K_TIMER], TimerOption.OFF),
            mirrorFront = enumOrDefault(map[K_MIRROR], MirrorMode.AUTO),
            filterId = if (FilterCatalog.isValid(filterId)) filterId else FilterCatalog.NONE_ID,
            filterStrength = map[K_FILTER_S]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: d.filterStrength,
            borderId = if (BorderCatalog.isValid(borderId)) borderId else BorderCatalog.NONE_ID,
            shutterSound = map[K_SOUND]?.toBooleanStrictOrNull() ?: d.shutterSound,
            saveFormat = enumOrDefault(map[K_FORMAT], SaveFormat.JPEG),
            quality = map[K_QUALITY]?.toIntOrNull()?.coerceIn(60, 100) ?: d.quality,
            lensFacing = map[K_LENS]?.toIntOrNull()
                ?.let { if (it == CameraParams.LENS_FACING_FRONT) it else CameraParams.LENS_FACING_BACK }
                ?: d.lensFacing,
            watermark = WatermarkConfig(
                enabled = map[K_WM_ON]?.toBooleanStrictOrNull() ?: d.watermark.enabled,
                showTime = map[K_WM_TIME]?.toBooleanStrictOrNull() ?: d.watermark.showTime,
                showDevice = map[K_WM_DEVICE]?.toBooleanStrictOrNull() ?: d.watermark.showDevice,
                customText = map[K_WM_TEXT].orEmpty(),
                position = enumOrDefault(map[K_WM_POS], WatermarkPosition.BOTTOM_LEFT),
                opacity = map[K_WM_OPACITY]?.toFloatOrNull()?.coerceIn(0.1f, 1f) ?: d.watermark.opacity,
                color = map[K_WM_COLOR]?.toLongOrNull()?.toInt() ?: d.watermark.color,
                fontSizeRatio = map[K_WM_SIZE]?.toFloatOrNull()?.coerceIn(0.01f, 0.10f) ?: d.watermark.fontSizeRatio,
            ),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

    /**
     * 往返一致性自检（供单测与调试使用）。
     * @return null 表示一致；否则返回第一处不一致的描述
     */
    fun roundTripDiff(p: CameraParams): String? {
        val back = decode(encode(p))
        return if (back == p) null else "encode/decode 不一致:\n原=$p\n回=$back"
    }
}
