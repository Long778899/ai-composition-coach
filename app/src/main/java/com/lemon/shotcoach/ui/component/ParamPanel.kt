package com.lemon.shotcoach.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.camera.AspectRatio
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.camera.FilterCatalog
import com.lemon.shotcoach.camera.FlashMode
import com.lemon.shotcoach.camera.FocusMode
import com.lemon.shotcoach.camera.GridStyle
import com.lemon.shotcoach.camera.IsoRange
import com.lemon.shotcoach.camera.MirrorMode
import com.lemon.shotcoach.camera.SaveFormat
import com.lemon.shotcoach.camera.ShutterRange
import com.lemon.shotcoach.camera.TempRange
import com.lemon.shotcoach.camera.TimerOption
import com.lemon.shotcoach.camera.WhiteBalancePreset
import com.lemon.shotcoach.camera.ZoomRange
import com.lemon.shotcoach.render.BorderCatalog
import com.lemon.shotcoach.ui.theme.ShotColors
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 参数调节面板。
 *
 * == 设计原则 ==
 *  1. **一切都是"可见即可调"**：没有藏在"高级"里的开关。相机参数的意义就在于
 *     能一边看取景一边调，藏起来等于没有。
 *  2. **"自动"永远是一个显式选项**，而不是"没设置"。用户点回"自动"必须能一键回到
 *     相机自己决定的状态 —— 这是与"手动"平权的选择，不是清空。
 *  3. **每个滑杆右侧的数值可点，点一下复位**。调歪了不用精确拖回去。
 *  4. **不拦截取景**：面板是半高度底部面板，上半屏始终能看到画面变化。
 *
 * @param ambientBrightness 最近一帧的平均亮度（0..255）。有值时"一键建议"可用。
 */
@Composable
fun ParamPanel(
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    onDismiss: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenBorder: () -> Unit,
    onOpenWatermark: () -> Unit,
    onSavePreset: () -> Unit,
    onResetAll: () -> Unit,
    ambientBrightness: Float? = null,
    zoomBounds: ClosedFloatingPointRange<Float> = ZoomRange.MIN..ZoomRange.MAX,
    zoomAvailable: Boolean = true,
) {
    PanelSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 头部 ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "拍摄参数",
                    color = ShotColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "恢复默认",
                    color = ShotColors.Accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onResetAll() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // ── 三个二级页面入口 ──────────────────────────────
            NavRow(
                title = "滤镜",
                value = FilterCatalog.byId(params.filterId).name,
                onClick = onOpenFilter,
            )
            NavRow(
                title = "边框",
                value = BorderCatalog.byId(params.borderId).name,
                onClick = onOpenBorder,
            )
            NavRow(
                title = "水印",
                value = if (params.watermark.enabled) "已开启" else "关闭",
                onClick = onOpenWatermark,
            )

            Divider()

            // ── 曝光补偿 ────────────────────────────────────
            ParamSliderRow(
                label = "曝光补偿",
                valueText = String.format(Locale.US, "%+.1f EV", params.ev),
                value = params.evIndex.toFloat(),
                range = EvRangeFloats,
                steps = EvRangeStepCount,
                onValueChange = { onChange(params.copy(evIndex = it.roundToInt())) },
                onReset = { onChange(params.copy(evIndex = 0)) },
            )
            if (ambientBrightness != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "按当前亮度建议 ISO / 快门 →",
                        color = ShotColors.Accent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ShotColors.SurfaceHigh)
                            .clickable {
                                val (iso, ns) = CameraParams.suggestExposure(ambientBrightness)
                                onChange(params.copy(iso = iso, shutterNs = ns))
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "环境亮度 ${ambientBrightness.roundToInt()}",
                        color = ShotColors.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            // ── ISO ────────────────────────────────────────
            EnumSection(
                title = "ISO",
                value = params.isoLabel,
                options = IsoStopsWithAuto,
                selected = params.iso,
                onSelect = { onChange(params.copy(iso = it)) },
                label = { if (it == CameraParams.ISO_AUTO) "自动" else it.toString() },
            )
            if (!params.isIsoAuto) {
                ParamSliderRow(
                    label = "ISO 微调",
                    valueText = params.iso.toString(),
                    value = params.iso.toFloat(),
                    range = IsoRange.MIN.toFloat()..IsoRange.MAX.toFloat(),
                    onValueChange = { onChange(params.copy(iso = IsoRange.snap(it.toInt()))) },
                    onReset = { onChange(params.copy(iso = CameraParams.ISO_AUTO)) },
                )
            }

            // ── 快门 ───────────────────────────────────────
            EnumSection(
                title = "快门速度",
                value = params.shutterLabel,
                options = ShutterStopsWithAuto,
                selected = params.shutterNs,
                onSelect = { onChange(params.copy(shutterNs = it)) },
                label = { CameraParams.formatShutter(it) },
            )

            // ── 白平衡 ─────────────────────────────────────
            EnumSection(
                title = "白平衡",
                value = if (params.wbPreset == WhiteBalancePreset.CUSTOM) {
                    "${params.wbTemperatureK}K"
                } else {
                    params.wbPreset.label
                },
                options = WhiteBalancePreset.values().toList(),
                selected = params.wbPreset,
                onSelect = { onChange(params.copy(wbPreset = it)) },
                label = { it.label },
            )
            if (params.wbPreset == WhiteBalancePreset.CUSTOM) {
                ParamSliderRow(
                    label = "色温",
                    valueText = "${params.wbTemperatureK} K",
                    value = params.wbTemperatureK.toFloat(),
                    range = TempRange.MIN_K.toFloat()..TempRange.MAX_K.toFloat(),
                    steps = (TempRange.MAX_K - TempRange.MIN_K) / TempRange.STEP_K - 1,
                    onValueChange = {
                        val snapped = (it / TempRange.STEP_K).roundToInt() * TempRange.STEP_K
                        onChange(params.copy(wbTemperatureK = snapped))
                    },
                    onReset = { onChange(params.copy(wbTemperatureK = 5500)) },
                )
            }

            // ── 对焦 ───────────────────────────────────────
            EnumSection(
                title = "对焦",
                value = params.focusMode.label,
                options = FocusMode.values().toList(),
                selected = params.focusMode,
                onSelect = { onChange(params.copy(focusMode = it)) },
                label = { it.label },
            )
            if (params.focusMode == FocusMode.MANUAL) {
                ParamSliderRow(
                    label = "手动对焦距离",
                    valueText = formatFocus(params.manualFocusDiopters),
                    value = params.manualFocusDiopters,
                    range = 0f..10f,
                    onValueChange = { onChange(params.copy(manualFocusDiopters = it)) },
                    onReset = { onChange(params.copy(manualFocusDiopters = 0f)) },
                )
            }

            // ── 变焦 ───────────────────────────────────────
            if (zoomAvailable) {
                EnumSection(
                    title = "变焦",
                    value = params.zoomLabel,
                    options = ZoomRange.QUICK,
                    selected = snapZoom(params.zoomRatio),
                    onSelect = { onChange(params.copy(zoomRatio = it)) },
                    label = { CameraParams.formatZoom(it) },
                )
                ParamSliderRow(
                    label = "连续变焦",
                    valueText = params.zoomLabel,
                    value = params.zoomRatio.coerceIn(zoomBounds.start, zoomBounds.endInclusive),
                    range = zoomBounds,
                    onValueChange = { onChange(params.copy(zoomRatio = it)) },
                    onReset = { onChange(params.copy(zoomRatio = 1f)) },
                )
            }

            // ── 闪光灯 ─────────────────────────────────────
            EnumSection(
                title = "闪光灯",
                value = params.flash.label,
                options = FlashMode.values().toList(),
                selected = params.flash,
                onSelect = { onChange(params.copy(flash = it)) },
                label = { it.label },
            )

            Divider()

            // ── 构图辅助 ────────────────────────────────────
            EnumSection(
                title = "构图网格",
                value = params.grid.label,
                options = GridStyle.values().toList(),
                selected = params.grid,
                onSelect = { onChange(params.copy(grid = it)) },
                label = { it.label },
            )
            EnumSection(
                title = "画幅比例",
                value = params.aspect.label,
                options = AspectRatio.values().toList(),
                selected = params.aspect,
                onSelect = { onChange(params.copy(aspect = it)) },
                label = { it.label },
            )
            ToggleRow(
                label = "水平仪",
                subtitle = "倾斜超过阈值时用琥珀色提示",
                checked = params.levelEnabled,
                onCheckedChange = { onChange(params.copy(levelEnabled = it)) },
            )
            ToggleRow(
                label = "构图引导",
                subtitle = "实时评分与调整建议（核心功能）",
                checked = params.guideEnabled,
                onCheckedChange = { onChange(params.copy(guideEnabled = it)) },
            )

            Divider()

            // ── 拍摄行为 ────────────────────────────────────
            EnumSection(
                title = "定时拍摄",
                value = params.timer.label,
                options = TimerOption.values().toList(),
                selected = params.timer,
                onSelect = { onChange(params.copy(timer = it)) },
                label = { it.label },
            )
            EnumSection(
                title = "前置镜像",
                value = params.mirrorFront.label,
                options = MirrorMode.values().toList(),
                selected = params.mirrorFront,
                onSelect = { onChange(params.copy(mirrorFront = it)) },
                label = { it.label },
            )
            ToggleRow(
                label = "快门音",
                checked = params.shutterSound,
                onCheckedChange = { onChange(params.copy(shutterSound = it)) },
            )

            Divider()

            // ── 输出 ───────────────────────────────────────
            EnumSection(
                title = "保存格式",
                value = params.saveFormat.label,
                options = SaveFormat.values().toList(),
                selected = params.saveFormat,
                onSelect = { onChange(params.copy(saveFormat = it)) },
                label = { it.label },
            )
            if (params.saveFormat == SaveFormat.JPEG) {
                ParamSliderRow(
                    label = "JPEG 质量",
                    valueText = "${params.quality}",
                    value = params.quality.toFloat(),
                    range = 60f..100f,
                    onValueChange = { onChange(params.copy(quality = it.roundToInt())) },
                    onReset = { onChange(params.copy(quality = 95)) },
                )
            }

            Divider()

            // ── 预设 ───────────────────────────────────────
            SectionHeader(title = "参数预设")
            NavRow(title = "保存当前参数为预设", value = null, onClick = onSavePreset)
            NavRow(title = "恢复出厂默认", value = null, onClick = onResetAll)

            BottomSpacer(extra = 32)
        }
    }
}

// ── 内部工具 ────────────────────────────────────────────────

/** ISO 档位 + 自动（自动排第一，符合"自动是显式选项"的原则） */
private val IsoStopsWithAuto: List<Int> = listOf(CameraParams.ISO_AUTO) + IsoRange.STOPS

/** 快门档位 + 自动 */
private val ShutterStopsWithAuto: List<Long> = listOf(CameraParams.SHUTTER_AUTO) + ShutterRange.STOPS

/** EV：index -6..6，共 13 个位置 → 中间 11 个分隔 */
private val EvRangeFloats = EvRange.MIN_INDEX.toFloat()..EvRange.MAX_INDEX.toFloat()
private val EvRangeStepCount = (EvRange.MAX_INDEX - EvRange.MIN_INDEX) - 1

/** "一行标题 + 一排选项"，参数面板里出现最多的一种排版 */
@Composable
private fun <T> EnumSection(
    title: String,
    value: String?,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
) {
    SectionHeader(title = title, value = value)
    OptionChips(
        options = options,
        isSelected = { it == selected },
        onSelect = onSelect,
        label = label,
    )
}

/** 屈光度 → 人类可读距离 */
private fun formatFocus(diopters: Float): String = when {
    diopters <= 0.05f -> "无穷远"
    else -> {
        val meters = 1f / diopters
        if (meters >= 1f) {
            String.format(Locale.US, "%.1f m", meters)
        } else {
            String.format(Locale.US, "%.0f cm", meters * 100)
        }
    }
}

/** 把连续变焦值吸附到最近的快捷档位，用于快捷 chips 的选中态 */
private fun snapZoom(ratio: Float): Float =
    ZoomRange.QUICK.minByOrNull { Math.abs(it - ratio) } ?: 1f
