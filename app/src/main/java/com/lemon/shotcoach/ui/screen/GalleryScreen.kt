package com.lemon.shotcoach.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.data.MediaQuery
import com.lemon.shotcoach.ui.component.EmptyState
import com.lemon.shotcoach.ui.component.PageScaffold
import com.lemon.shotcoach.ui.theme.ShotColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册页。
 *
 * 只显示**本应用拍的照片**（Pictures/ShotCoach），因此不需要相册读取权限。
 * 点击看大图，大图页可删除。
 *
 * 缩略图在 IO 线程解码 —— 网格一屏十几张图，在主线程解码会明显掉帧。
 */
@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf(MediaQuery.recent(context, limit = 120)) }
    var viewerIndex by remember { mutableStateOf(-1) }
    var viewerBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var confirmIndex by remember { mutableStateOf(-1) }

    // 打开大图时才解码原图（带二次采样，避免 OOM）
    LaunchedEffect(viewerIndex, photos) {
        viewerBitmap = null
        val item = photos.getOrNull(viewerIndex) ?: return@LaunchedEffect
        viewerBitmap = withContext(Dispatchers.IO) {
            MediaQuery.fullImage(context, item.uri, maxSize = 1600)
        }
    }

    PageScaffold(
        title = "相册",
        onBack = onBack,
        actions = {
            TextAction(
                text = "刷新",
                onClick = { photos = MediaQuery.recent(context, limit = 120) },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (photos.isEmpty()) {
                EmptyState(
                    text = "还没有照片。\n在取景页按下快门后，成片会出现在这里。",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "共 ${photos.size} 张 · 仅显示本应用拍摄的照片",
                        color = ShotColors.TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(photos, key = { _, it -> it.uri.toString() }) { index, item ->
                            PhotoCell(
                                item = item,
                                onClick = { viewerIndex = index },
                                onLongClick = { confirmIndex = index },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 大图查看 ─────────────────────────────────────────
    val viewing = photos.getOrNull(viewerIndex)
    if (viewing != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF2000000)),
        ) {
            val bmp = viewerBitmap
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = viewing.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(vertical = 60.dp),
                )
            } else {
                Text(
                    text = "载入中…",
                    color = ShotColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Text(
                text = "关闭",
                color = ShotColors.TextPrimary,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x66000000))
                    .clickable { viewerIndex = -1 }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )

            Text(
                text = "删除",
                color = ShotColors.ScorePoor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x66000000))
                    .clickable { confirmIndex = viewerIndex }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${viewing.width}×${viewing.height} · ${formatSize(viewing.sizeBytes)}",
                    color = ShotColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }

    // ── 删除确认 ─────────────────────────────────────────
    val toDelete = photos.getOrNull(confirmIndex)
    if (toDelete != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xC0000000))
                .clickable { confirmIndex = -1 },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ShotColors.Surface)
                    .clickable(enabled = false) { }
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "删除这张照片？",
                    color = ShotColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = toDelete.displayName,
                    color = ShotColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(18.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "删除",
                        color = Color(0xFF1A1A1A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(ShotColors.ScorePoor)
                            .clickable {
                                MediaQuery.delete(context, toDelete.uri)
                                photos = MediaQuery.recent(context, limit = 120)
                                confirmIndex = -1
                                viewerIndex = -1
                            }
                            .padding(horizontal = 34.dp, vertical = 11.dp),
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    item: MediaQuery.PhotoItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    // 在 IO 线程解码缩略图；key 为 uri，照片列表刷新时自动重载
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = item.uri) {
        value = withContext(Dispatchers.IO) { MediaQuery.thumbnail(context, item.uri, 320) }
    }

    val bmp = thumb
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(ShotColors.SurfaceHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(text = "…", color = ShotColors.TextDisabled, fontSize = 12.sp)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
