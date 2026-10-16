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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.camera.FilterCatalog
import com.lemon.shotcoach.data.PreviewFrameStore
import com.lemon.shotcoach.data.TextFileSaver
import com.lemon.shotcoach.filter.FilterEngine
import com.lemon.shotcoach.filter.LutExporter
import com.lemon.shotcoach.filter.SwatchPattern
import com.lemon.shotcoach.ui.component.FilterStrengthRow
import com.lemon.shotcoach.ui.component.FilterStrip
import com.lemon.shotcoach.ui.component.PageScaffold
import com.lemon.shotcoach.ui.component.SectionHeader
import com.lemon.shotcoach.ui.theme.ShotColors
import kotlin.math.roundToInt

/**
 * 滤镜页。
 *
 * == 核心：预览是真的 ==
 * 大图预览 [buildPreview] 用的是**当前取景帧**（[PreviewFrameStore]），
 * 套的就是导出时会用的**同一条三线性插值路径** —— 所以这里看到什么，
 * 成片就是什么，不存在"预览好看导出崩"。
 *
 * == 为什么提供导出 .cube ==
 * 这是"滤镜 = 算法而非素材"的直接兑现：用户可以把内置滤镜导成标准 .cube
 * 拿到剪辑软件里用。既然没有版权素材，也就没有理由把算法锁在 App 内。
 */
@Composable
fun FilterScreen(
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    val spec = FilterCatalog.byId(params.filterId)

    // 订阅取景帧更新 + 参数变化，重算预览
    val frameVersion = PreviewFrameStore.version
    val strengthBucket = (params.filterStrength * 10f).roundToInt()
    val preview = remember(frameVersion, params.filterId, strengthBucket) {
        buildPreview(params)
    }

    PageScaffold(
        title = "滤镜",
        onBack = onBack,
        actions = {
            TextAction(
                text = "导出 .cube",
                onClick = {
                    val text = LutExporter.exportFilter(spec, params.filterStrength)
                    val uri = TextFileSaver.saveToDownloads(
                        context = context,
                        fileName = "shotcoach_${spec.id}.cube",
                        mimeType = "text/plain",
                        content = text,
                    )
                    status = if (uri != null) "已导出到 下载/ShotCoach/shotcoach_${spec.id}.cube" else "导出失败"
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // 大图预览
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "滤镜预览",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )

            Text(
                text = FilterEngine.describe(spec, params.filterStrength),
                color = ShotColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
            )

            status?.let { s ->
                Text(
                    text = s,
                    color = ShotColors.Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp),
                )
            }

            Spacer(Modifier.height(6.dp))
            FilterStrengthRow(params = params, onChange = onChange)

            SectionHeader(
                title = "选择滤镜",
                value = "共 ${FilterCatalog.ALL.size} 个",
            )
            FilterStrip(
                params = params,
                onChange = onChange,
                baseSwatch = PreviewFrameStore.current(),
                thumbnailSize = 96,
            )

            Spacer(Modifier.height(10.dp))
            HintText(
                "所有滤镜由色彩参数实时烘焙而成（色温、色调、饱和、对比、分离色调、" +
                    "褪色、黑白等），不含任何 LUT 贴图。滑杆调强度时缩略图同步变化。",
            )
            Spacer(Modifier.height(36.dp))
        }
    }
}

/**
 * 构建预览图。
 *
 * 优先用当前取景帧；没有帧（刚进页面 / 相机未就绪）时回落到程序化色卡，
 * 保证任何时刻都有内容可看。
 */
private fun buildPreview(params: CameraParams): Bitmap {
    val base = PreviewFrameStore.current()
    val src = if (base != null) {
        base
    } else {
        val w = 480
        val h = 360
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(SwatchPattern.build(w, h), 0, w, 0, 0, w, h)
        }
    }
    return FilterEngine.applyTrilinearToBitmap(
        src = src,
        spec = FilterCatalog.byId(params.filterId),
        strength = params.filterStrength,
    )
}
