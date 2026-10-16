package com.lemon.shotcoach.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.ui.theme.ShotColors

/**
 * 通用 UI 组件库。
 *
 * 抽出来的动机：参数面板里同一种"一行标题 + 一排选项"的排版要重复十几次，
 * 每一处都手写 Rows/LazyRow 会导致间距、圆角、选中态不一致 —— 那是
 * 一眼就能看出来的廉价感。统一到一个组件后，改一次全局生效。
 */

/** 底部面板外壳（参数面板 / 滤镜面板 / 边框面板都用它） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelSheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShotColors.Surface,
        scrimColor = Color(0x99000000),
        dragHandle = { DragHandle() },
    ) {
        content()
    }
}

@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 4.dp)
                .clip(CircleShape)
                .background(ShotColors.SurfaceHigher),
        )
    }
}

/** 分区标题 + 可选右侧数值 */
@Composable
fun SectionHeader(
    title: String,
    value: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = ShotColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
        if (value != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = value,
                color = ShotColors.Accent,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * 一排可选项（横向滚动）。
 *
 * 为什么用 LazyRow 而不是 FlowRow：选项多起来时 FlowRow 会把面板撑高，
 * 导致底部的快门键被挤出屏幕。横向滚动把高度锁成一行，用户滑动找选项
 * 也比上下找更符合"参数条"的心智模型。
 */
@Composable
fun <T> OptionChips(
    options: List<T>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    accent: Color = ShotColors.Accent,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { option ->
            val selected = isSelected(option)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) accent else ShotColors.SurfaceHigh)
                    .border(
                        width = 1.dp,
                        color = if (selected) accent else Color(0x1FFFFFFF),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    color = if (selected) ShotColors.OnAccent else ShotColors.TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 带数值显示的滑杆行。双击标题区（点右侧数值）可复位到默认值。
 */
@Composable
fun ParamSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onReset: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (enabled) ShotColors.TextPrimary else ShotColors.TextDisabled,
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = valueText,
                color = if (enabled) ShotColors.Accent else ShotColors.TextDisabled,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = if (enabled && onReset != null) {
                    Modifier.clickable { onReset() }
                } else {
                    Modifier
                },
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.padding(horizontal = 16.dp),
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) ShotColors.Accent else ShotColors.TextDisabled,
                activeTrackColor = ShotColors.Accent,
                inactiveTrackColor = ShotColors.SurfaceHigher,
            ),
        )
    }
}

/** 开关行 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = ShotColors.TextPrimary, fontSize = 14.sp)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = ShotColors.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ShotColors.OnAccent,
                checkedTrackColor = ShotColors.Accent,
                uncheckedThumbColor = ShotColors.TextSecondary,
                uncheckedTrackColor = ShotColors.SurfaceHigher,
            ),
        )
    }
}

/** 跳转行（进入二级页面） */
@Composable
fun NavRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, color = ShotColors.TextPrimary, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                color = ShotColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(text = "›", color = ShotColors.TextSecondary, fontSize = 20.sp)
    }
}

/** 分割线 */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x14FFFFFF)),
    )
}

/** 底部留白，避免最后一个控件被系统手势条挡住 */
@Composable
fun BottomSpacer(extra: Int = 24) {
    Spacer(Modifier.height(extra.dp))
}

/** 倒计时大数字覆盖层 */
@Composable
fun CountdownOverlay(seconds: Int, modifier: Modifier = Modifier) {
    if (seconds <= 0) return
    Box(
        modifier = modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seconds.toString(),
            color = ShotColors.Accent,
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}

/** 空状态 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = ShotColors.TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** 带标题的页面外壳（二级页面通用） */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ShotColors.Black),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "‹", color = ShotColors.TextPrimary, fontSize = 26.sp)
            }
            Text(
                text = title,
                color = ShotColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            actions()
        }
        content()
    }
}

/** 提示条（保存成功/失败） */
@Composable
fun StatusBanner(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isError) Color(0x33E8654F) else Color(0x334CD98A))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = if (isError) ShotColors.ScorePoor else ShotColors.ScoreGood,
            fontSize = 12.sp,
        )
    }
}

/** 小圆点标记（旋转指示、录制点等） */
@Composable
fun Dot(color: Color, size: Int = 8, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 占位宽度用于 Row 内对齐 */
@Composable
fun HSpace(width: Int) {
    Spacer(Modifier.width(width.dp))
}
