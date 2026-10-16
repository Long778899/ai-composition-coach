package com.lemon.shotcoach.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.data.PreviewFrameStore
import com.lemon.shotcoach.filter.SwatchPattern
import com.lemon.shotcoach.render.BorderCatalog
import com.lemon.shotcoach.render.BorderKind
import com.lemon.shotcoach.render.BorderRenderer
import com.lemon.shotcoach.ui.component.BorderStrip
import com.lemon.shotcoach.ui.component.PageScaffold
import com.lemon.shotcoach.ui.component.SectionHeader
import com.lemon.shotcoach.ui.theme.ShotColors

/**
 * 边框页。
 *
 * 预览走 [BorderRenderer.render] —— **与导出成片完全同一条代码路径**。
 * 这一点很重要：如果预览用一套简化绘制、导出用另一套，用户就会遇到
 * "预览好看但导出不一样"的经典问题。这里从根上共用实现。
 */
@Composable
fun BorderScreen(
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    onBack: () -> Unit,
) {
    val spec = BorderCatalog.byId(params.borderId)
    val frameVersion = PreviewFrameStore.version

    val preview = remember(frameVersion, params.borderId) { buildBorderPreview(params) }

    PageScaffold(title = "照片边框", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "边框预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )

            Text(
                text = describeBorder(spec.kind, spec.name),
                color = ShotColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
            )

            SectionHeader(title = "选择边框", value = "共 ${BorderCatalog.ALL.size} 个")
            BorderStrip(params = params, onChange = onChange)

            Spacer(Modifier.height(10.dp))
            HintText(
                "边框全部由代码绘制并按输出分辨率等比铺开，所以 4K 输出不会像" +
                    "1080p 素材那样发虚。宽度、颜色、圆角、留白等参数在 " +
                    "BorderCatalog 中定义，可按需扩展。",
            )
            Spacer(Modifier.height(36.dp))
        }
    }
}

private fun describeBorder(kind: BorderKind, name: String): String = when (kind) {
    BorderKind.NONE -> "无边框：输出原始画幅。"
    BorderKind.SOLID -> "$name：纯色外框 + 内层留白，最经典的画廊装裱样式。"
    BorderKind.DOUBLE_LINE -> "$name：双细线内框，偏杂志排版感。"
    BorderKind.ROUNDED -> "$name：四角圆角裁切 + 同色描边。"
    BorderKind.POLAROID -> "$name：上方等宽、下方加宽的白边，留出写字的区域。"
    BorderKind.FILM -> "$name：上下黑色片基 + 齿孔，附机型/时间条。"
    BorderKind.VIGNETTE_FRAME -> "$name：内缘压暗 + 极细描边，用于收拢视线。"
}

/**
 * 用当前取景帧（无则色卡）套边框，得到 4:3 预览。
 *
 * 缩略图分辨率压到 720 宽：既能看清胶片齿孔，又让每次重算保持在毫秒级。
 */
private fun buildBorderPreview(params: CameraParams): Bitmap {
    val base = PreviewFrameStore.current()
    if (base != null) {
        return BorderRenderer.render(base, BorderCatalog.byId(params.borderId), label = "SHOTCOACH")
    }
    val w = 720
    val h = 540
    val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    src.setPixels(SwatchPattern.build(w, h), 0, w, 0, 0, w, h)
    return BorderRenderer.render(src, BorderCatalog.byId(params.borderId), label = "SHOTCOACH")
}
