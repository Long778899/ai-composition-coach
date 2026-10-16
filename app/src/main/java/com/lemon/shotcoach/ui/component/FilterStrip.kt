package com.lemon.shotcoach.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.camera.FilterCatalog
import com.lemon.shotcoach.camera.FilterSpec
import com.lemon.shotcoach.filter.FilterEngine
import com.lemon.shotcoach.ui.theme.ShotColors
import kotlin.math.roundToInt

/**
 * 滤镜选择条。
 *
 * 每个缩略图都是**真的把当前滤镜烘出来**（不是预存的小图），所以：
 *  - 调强度时缩略图会跟着变，所见即所得；
 *  - 有色卡兜底，相机没出帧也不白屏。
 *
 * @param baseSwatch 当前取景帧的小图。为 null 时用程序化色卡。
 */
@Composable
fun FilterStrip(
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    baseSwatch: Bitmap? = null,
    thumbnailSize: Int = 84,
    modifier: Modifier = Modifier,
) {
    // 强度量化到 1/10，避免拖动滑杆时每 0.01 都重算一屏缩略图
    val strengthBucket = remember(params.filterStrength) {
        (params.filterStrength * 10f).roundToInt()
    }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(FilterCatalog.ALL, key = { it.id }) { spec ->
            val selected = spec.id == params.filterId
            val thumb = remember(spec.id, baseSwatch, strengthBucket, thumbnailSize) {
                FilterEngine.previewSwatch(
                    base = baseSwatch,
                    spec = spec,
                    strength = params.filterStrength,
                    size = thumbnailSize,
                )
            }
            FilterThumb(
                bitmap = thumb,
                spec = spec,
                selected = selected,
                onClick = { onChange(params.copy(filterId = spec.id)) },
            )
        }
    }
}

@Composable
private fun FilterThumb(
    bitmap: Bitmap,
    spec: FilterSpec,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(vertical = 4.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = spec.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) ShotColors.Accent else Color(0x22FFFFFF),
                    shape = RoundedCornerShape(10.dp),
                ),
        )
        Text(
            text = spec.name,
            color = if (selected) ShotColors.Accent else ShotColors.TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 5.dp)
                .size(width = 66.dp, height = 16.dp),
        )
    }
}

/** 滤镜强度滑杆（选中非"原图"时显示） */
@Composable
fun FilterStrengthRow(
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (params.filterId == FilterCatalog.NONE_ID) return
    ParamSliderRow(
        label = "滤镜强度",
        valueText = "${(params.filterStrength * 100).roundToInt()}%",
        value = params.filterStrength,
        range = 0f..1f,
        onValueChange = { onChange(params.copy(filterStrength = it)) },
        onReset = { onChange(params.copy(filterStrength = 1f)) },
        modifier = modifier,
    )
}

/** 单独一个滤镜的大预览（滤镜详情用） */
@Composable
fun FilterPreviewCard(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ShotColors.SurfaceHigh),
    )
}
