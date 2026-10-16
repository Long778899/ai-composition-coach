package com.lemon.shotcoach.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.camera.FilterCatalog
import com.lemon.shotcoach.camera.SaveFormat
import com.lemon.shotcoach.data.SettingsRepository
import com.lemon.shotcoach.ui.component.NavRow
import com.lemon.shotcoach.ui.component.OptionChips
import com.lemon.shotcoach.ui.component.PageScaffold
import com.lemon.shotcoach.ui.component.ParamSliderRow
import com.lemon.shotcoach.ui.component.SectionHeader
import com.lemon.shotcoach.ui.component.ToggleRow
import com.lemon.shotcoach.ui.theme.ShotColors

/**
 * 设置页。
 *
 * == 两类设置的边界 ==
 *  - **拍摄参数**（格式、质量、镜头…）属于 [CameraParams]，改完立刻生效并持久化；
 *  - **应用偏好 + 分析调参**属于 [SettingsRepository]，其中分析调参
 *    （抽帧、平滑、驻留）在**重新进入取景页后**生效 —— 因为分析管线的
 *    这些参数在管线构造时注入。界面上明确写了这一点，避免用户以为没生效。
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    onBack: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    PageScaffold(title = "设置", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 分析（前置引擎调参）──────────────────────────
            SectionHeader(title = "构图分析")
            PrefSlider(
                label = "抽帧间隔",
                initial = settings.analysisEveryNFrames.toFloat(),
                range = 1f..10f,
                steps = 8,
                format = { "${it.toInt()} 帧" },
                onSet = { settings.analysisEveryNFrames = it.toInt() },
            )
            PrefSlider(
                label = "平滑强度",
                initial = settings.emaAlpha,
                range = 0.1f..1f,
                steps = 8,
                format = { "α ${"%.2f".format(it)}" },
                onSet = { settings.emaAlpha = it },
            )
            PrefSlider(
                label = "提示驻留",
                initial = settings.hintDwellMs.toFloat(),
                range = 200f..3000f,
                steps = 13,
                format = { "${it.toLong()} ms" },
                onSet = { settings.hintDwellMs = it.toLong() },
            )
            PrefToggle(
                label = "人脸检测通道",
                subtitle = "关闭可省约 2ms/帧，构图评分会忽略人脸",
                initial = settings.faceDetectEnabled,
                onSet = { settings.faceDetectEnabled = it },
            )
            PrefToggle(
                label = "显示评分徽标",
                subtitle = "在取景页左上角显示四项分值与帧率",
                initial = settings.showScoreBadge,
                onSet = { settings.showScoreBadge = it },
            )
            HintText(
                "以上分析参数在**下次进入取景页**时生效（分析管线在进入时构建）。" +
                    "平滑强度越大提示越稳但越迟钝；驻留时间决定一条建议最少停留多久，用来防跳。",
            )

            Spacer(Modifier.height(14.dp))

            // ── 拍摄 ─────────────────────────────────────
            SectionHeader(title = "拍摄")
            PrefToggle(
                label = "保存到系统相册",
                subtitle = "写入 Pictures/ShotCoach，无需存储权限",
                initial = settings.saveToAlbum,
                onSet = { settings.saveToAlbum = it },
            )
            PrefToggle(
                label = "音量键当快门",
                subtitle = "按音量加/减键直接拍摄",
                initial = settings.volumeKeyShutter,
                onSet = { settings.volumeKeyShutter = it },
            )
            PrefToggle(
                label = "拍摄时屏幕常亮",
                initial = settings.keepScreenOn,
                onSet = { settings.keepScreenOn = it },
            )

            SectionHeader(title = "保存格式")
            OptionChips(
                options = SaveFormat.values().toList(),
                isSelected = { it == params.saveFormat },
                onSelect = { f -> onChange(params.copy(saveFormat = f)) },
                label = { it.label },
            )
            Text(
                text = "HEIC 由系统编码器写入，体积更小。编码失败时不会留下坏图。",
                color = ShotColors.TextDisabled,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp),
            )

            ParamSliderRow(
                label = "画质",
                valueText = "${params.quality}",
                value = params.quality.toFloat(),
                range = 60f..100f,
                steps = 7,
                enabled = params.saveFormat == SaveFormat.JPEG,
                onValueChange = { v -> onChange(params.copy(quality = v.toInt())) },
                onReset = { onChange(params.copy(quality = 95)) },
            )
            HintText("画质仅对 JPEG 有效；PNG 无损、HEIC 由编码器自行决定。")

            Spacer(Modifier.height(14.dp))

            // ── 参数与预设 ────────────────────────────────
            SectionHeader(title = "参数")
            NavRow(
                title = "参数预设",
                value = "${settings.listPresets().size} 个",
                onClick = onOpenPresets,
            )
            NavRow(
                title = "当前滤镜",
                value = FilterCatalog.byId(params.filterId).name,
                onClick = { },
            )

            Spacer(Modifier.height(16.dp))
            TextAction(
                text = "恢复出厂参数（不影响应用偏好）",
                accent = false,
                onClick = { settings.resetParams() },
            )

            Spacer(Modifier.height(14.dp))

            // ── 其他 ─────────────────────────────────────
            SectionHeader(title = "其他")
            NavRow(title = "关于与隐私", value = null, onClick = onOpenAbout)

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 偏好滑杆。
 *
 * [SettingsRepository] 的属性是普通 var（不是 Compose 状态），所以这里
 * 必须用本地 state 镜像它 —— 否则拖动滑杆界面不会跟手。
 */
@Composable
private fun PrefSlider(
    label: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onSet: (Float) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    ParamSliderRow(
        label = label,
        valueText = format(v),
        value = v,
        range = range,
        steps = steps,
        onValueChange = { nv ->
            v = nv
            onSet(nv)
        },
    )
}

@Composable
private fun PrefToggle(
    label: String,
    subtitle: String? = null,
    initial: Boolean,
    onSet: (Boolean) -> Unit,
) {
    var checked by remember { mutableStateOf(initial) }
    ToggleRow(
        label = label,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = { nv ->
            checked = nv
            onSet(nv)
        },
    )
}
