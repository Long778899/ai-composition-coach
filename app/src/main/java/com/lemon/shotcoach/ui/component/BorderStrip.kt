package com.lemon.shotcoach.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import com.lemon.shotcoach.filter.SwatchPattern
import com.lemon.shotcoach.render.BorderCatalog
import com.lemon.shotcoach.render.BorderRenderer
import com.lemon.shotcoach.render.BorderSpec
import com.lemon.shotcoach.ui.theme.ShotColors

/**
 * 边框选择条。
 *
 * 缩略图是**真的用 [BorderRenderer] 画出来的**（底下垫程序化色卡当"照片"），
 * 所以用户看到的就是导出后得到的边框，不存在"预览与成片不一致"。
 */
@Composable
fun BorderStrip(
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(BorderCatalog.ALL, key = { it.id }) { spec ->
            val thumb = remember(spec.id) { renderBorderThumb(spec) }
            val selected = spec.id == params.borderId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onChange(params.copy(borderId = spec.id)) }
                    .padding(vertical = 4.dp),
            ) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = spec.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(width = 66.dp, height = 66.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) ShotColors.Accent else Color(0x22FFFFFF),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(3.dp),
                )
                Text(
                    text = spec.name,
                    color = if (selected) ShotColors.Accent else ShotColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(width = 68.dp, height = 16.dp),
                )
            }
        }
    }
}

/**
 * 渲染一张 4:3 的边框缩略图：色卡当"照片" → 套边框 → 输出。
 *
 * 缩略图分辨率取 168×126：够看清胶片孔的细节，又只有 6 万像素，
 * 十几个边框的缩略图总耗时在 10ms 量级。
 */
private fun renderBorderThumb(spec: BorderSpec): Bitmap {
    val w = 168
    val h = 126
    val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    src.setPixels(SwatchPattern.build(w, h), 0, w, 0, 0, w, h)
    return BorderRenderer.render(src, spec, label = "SHOTCOACH")
}
