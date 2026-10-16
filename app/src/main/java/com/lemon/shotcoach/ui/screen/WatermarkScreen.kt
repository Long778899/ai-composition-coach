package com.lemon.shotcoach.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.camera.WatermarkConfig
import com.lemon.shotcoach.camera.WatermarkPosition
import com.lemon.shotcoach.data.PreviewFrameStore
import com.lemon.shotcoach.filter.SwatchPattern
import com.lemon.shotcoach.render.WatermarkRenderer
import com.lemon.shotcoach.ui.component.OptionChips
import com.lemon.shotcoach.ui.component.PageScaffold
import com.lemon.shotcoach.ui.component.ParamSliderRow
import com.lemon.shotcoach.ui.component.SectionHeader
import com.lemon.shotcoach.ui.component.ToggleRow
import com.lemon.shotcoach.ui.theme.ShotColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 水印页。
 *
 * == 预览每秒刷新 ==
 * 预览里的时间戳是真的：用 [LaunchedEffect] 每秒 bump 一次版本号重绘，
 * 所以用户能看到秒针在走，确认时间戳确实是"拍摄时刻"而不是静态文字。
 *
 * == 水印画在照片本体内 ==
 * 顺序是 滤镜 → 水印 → 边框（见 [com.lemon.shotcoach.render.PhotoProcessor]）。
 * 所以边框的白边上不会出现水印 —— 这符合直觉：水印属于照片，不属于相框。
 */
@Composable
fun WatermarkScreen(
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    onBack: () -> Unit,
) {
    val cfg = params.watermark

    // 每秒重绘一次，让预览里的时间戳走起来
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val frameVersion = PreviewFrameStore.version
    val preview = remember(frameVersion, tick, cfg) { buildWatermarkPreview(params) }

    val update: ((WatermarkConfig) -> WatermarkConfig) -> Unit = { f ->
        onChange(params.copy(watermark = f(cfg)))
    }

    PageScaffold(title = "水印", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "水印预览",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )

            Spacer(Modifier.height(8.dp))

            ToggleRow(
                label = "启用水印",
                subtitle = "关闭后成片不带任何角标",
                checked = cfg.enabled,
                onCheckedChange = { v -> update { it.copy(enabled = v) } },
            )

            SectionHeader(title = "显示内容")
            ToggleRow(
                label = "拍摄时间",
                checked = cfg.showTime,
                onCheckedChange = { v -> update { it.copy(showTime = v) } },
            )
            ToggleRow(
                label = "机型标识",
                subtitle = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                checked = cfg.showDevice,
                onCheckedChange = { v -> update { it.copy(showDevice = v) } },
            )

            SectionHeader(title = "自定义文字")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ShotColors.Surface)
                    .padding(horizontal = 13.dp, vertical = 12.dp),
            ) {
                if (cfg.customText.isEmpty()) {
                    Text(
                        text = "例如：致 2026 · 旅行手记",
                        color = ShotColors.TextDisabled,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = cfg.customText,
                    onValueChange = { t -> update { it.copy(customText = t.take(40)) } },
                    singleLine = true,
                    textStyle = TextStyle(color = ShotColors.TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(ShotColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HintText("留空则不显示自定义文字；最多 40 字。")

            SectionHeader(title = "位置")
            OptionChips(
                options = WatermarkPosition.values().toList(),
                isSelected = { it == cfg.position },
                onSelect = { p -> update { it.copy(position = p) } },
                label = { it.label },
            )

            SectionHeader(title = "不透明度", value = "${(cfg.opacity * 100).roundToInt()}%")
            ParamSliderRow(
                label = "不透明度",
                valueText = "${(cfg.opacity * 100).roundToInt()}%",
                value = cfg.opacity,
                range = 0.1f..1f,
                onValueChange = { v -> update { it.copy(opacity = v) } },
                onReset = { update { it.copy(opacity = 0.7f) } },
            )

            SectionHeader(title = "字号", value = "${(cfg.fontSizeRatio * 1000).roundToInt() / 10f}% 短边")
            ParamSliderRow(
                label = "字号",
                valueText = "${(cfg.fontSizeRatio * 1000).roundToInt() / 10f}%",
                value = cfg.fontSizeRatio,
                range = 0.015f..0.070f,
                onValueChange = { v -> update { it.copy(fontSizeRatio = v) } },
                onReset = { update { it.copy(fontSizeRatio = 0.030f) } },
            )

            SectionHeader(title = "颜色")
            ColorChips(
                selected = cfg.color,
                onSelect = { c -> update { it.copy(color = c) } },
            )

            Spacer(Modifier.height(10.dp))
            HintText(
                "水印绘制在照片本体内（边框之外不会出现），并对浅色背景自动加深色描边，" +
                    "保证在任何画面上都可读。字号按短边比例缩放，换分辨率不会变形。",
            )
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun ColorChips(selected: Int, onSelect: (Int) -> Unit) {
    val colors = listOf(
        "白" to 0xFFFFFFFF.toInt(),
        "黑" to 0xFF000000.toInt(),
        "琥珀" to 0xFFFFC24B.toInt(),
        "朱红" to 0xFFE8654F.toInt(),
    )
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        items(colors.size) { i ->
            val (label, value) = colors[i]
            val isSel = value == selected
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSel) ShotColors.Accent else ShotColors.Surface)
                    .clickableChip { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    color = if (isSel) Color(0xFF1A1A1A) else ShotColors.TextPrimary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun Modifier.clickableChip(onClick: () -> Unit): Modifier =
    this.then(androidx.compose.foundation.clickable(onClick = onClick))

/** 用当前取景帧（无则色卡）套水印 */
private fun buildWatermarkPreview(params: CameraParams): Bitmap {
    val base = PreviewFrameStore.current()
    val src = base ?: run {
        val w = 720
        val h = 540
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(SwatchPattern.build(w, h), 0, w, 0, 0, w, h)
        }
    }
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    WatermarkRenderer.draw(
        canvas = Canvas(out),
        cfg = params.watermark,
        imageWidth = out.width,
        imageHeight = out.height,
        takenAt = System.currentTimeMillis(),
        deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    )
    return out
}
