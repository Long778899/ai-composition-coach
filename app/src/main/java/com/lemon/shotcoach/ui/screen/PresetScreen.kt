package com.lemon.shotcoach.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.data.SettingsRepository
import com.lemon.shotcoach.data.TextFileSaver
import com.lemon.shotcoach.ui.component.EmptyState
import com.lemon.shotcoach.ui.component.PageScaffold
import com.lemon.shotcoach.ui.component.SectionHeader
import com.lemon.shotcoach.ui.theme.ShotColors

/**
 * 参数预设页。
 *
 * == 为什么预设重要 ==
 * 参数面板有 20 多个可调项。拍同一类题材（比如夜拍、人像）时用户不想
 * 每次重调。预设就是"把当前全部参数存成一个名字"。
 *
 * 实现上直接复用 [SettingsRepository.savePreset] / [loadPreset] /
 * [deletePreset]，底层就是 [com.lemon.shotcoach.data.CameraParamsCodec]
 * 的键值对快照 —— 新增参数时只要同步 ALL_PARAM_KEYS，预设自动包含它。
 */
@Composable
fun PresetScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var names by remember { mutableStateOf(settings.listPresets()) }
    var newName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    PageScaffold(title = "参数预设", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 保存当前参数 ─────────────────────────────
            SectionHeader(title = "保存当前参数")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ShotColors.Surface)
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                ) {
                    if (newName.isEmpty()) {
                        Text(
                            text = "预设名称，如「夜拍」「人像」",
                            color = ShotColors.TextDisabled,
                            fontSize = 13.sp,
                        )
                    }
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it.take(24) },
                        singleLine = true,
                        textStyle = TextStyle(color = ShotColors.TextPrimary, fontSize = 13.sp),
                        cursorBrush = SolidColor(ShotColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.padding(start = 8.dp))
                TextAction(
                    text = "保存",
                    onClick = {
                        val name = newName.trim()
                        if (name.isEmpty()) {
                            status = "请输入名称"
                        } else {
                            settings.savePreset(name)
                            names = settings.listPresets()
                            newName = ""
                            status = "已保存「$name」"
                        }
                    },
                )
            }
            HintText("预设只保存拍摄参数（曝光、ISO、快门、白平衡、对焦、变焦、滤镜、边框、水印等），不包含分析调参。")

            status?.let { s ->
                Text(
                    text = s,
                    color = ShotColors.Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── 预设列表 ─────────────────────────────────
            SectionHeader(title = "已有预设", value = "${names.size} 个")
            if (names.isEmpty()) {
                EmptyState("还没有预设。\n调好参数后在上面命名保存即可。")
            } else {
                for (name in names) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ShotColors.Surface)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            color = ShotColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "应用",
                            color = ShotColors.Accent,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(ShotColors.SurfaceHigh)
                                .clickableNoRipple {
                                    val ok = settings.loadPreset(name)
                                    status = if (ok) "已应用「$name」" else "加载失败"
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.padding(start = 6.dp))
                        Text(
                            text = "导出",
                            color = ShotColors.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(ShotColors.SurfaceHigh)
                                .clickableNoRipple {
                                    val text = settings.exportPresetAsCubeText(name)
                                    val uri = text?.let {
                                        TextFileSaver.saveToDownloads(
                                            context = context,
                                            fileName = "shotcoach_preset_$name.txt",
                                            mimeType = "text/plain",
                                            content = it,
                                        )
                                    }
                                    status = if (uri != null) "已导出到 下载/ShotCoach/" else "导出失败"
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                        Spacer(Modifier.padding(start = 6.dp))
                        Text(
                            text = "删除",
                            color = ShotColors.ScorePoor,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(ShotColors.SurfaceHigh)
                                .clickableNoRipple {
                                    settings.deletePreset(name)
                                    names = settings.listPresets()
                                    status = "已删除「$name」"
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HintText(
                "「应用」会立刻把预设写入当前参数（含取景页正在使用的参数），无需重启。" +
                    "「导出」把预设写成可读文本存到 下载/ShotCoach/，便于备份或分享。",
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        androidx.compose.foundation.clickable(
            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
            indication = null,
            onClick = onClick,
        ),
    )
