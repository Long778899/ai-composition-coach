package com.lemon.shotcoach.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.ui.component.Dot
import com.lemon.shotcoach.ui.theme.ShotColors

/**
 * 首次启动引导。
 *
 * 刻意**不讲功能清单**，只讲三件用户真正需要预先知道的事：
 *  1. 这个 App 会实时给建议（不是拍完才修图）；
 *  2. 参数全部可调，并且都能在取景时看到变化；
 *  3. 它不联网 —— 这一点决定了用户是否愿意把摄像头交给它。
 *
 * 第 3 点放在最后并单独成页，是刻意的：信任问题是这类 App 最大的转化门槛。
 */
@Composable
fun GuideScreen(
    onStart: () -> Unit,
    onSkip: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }

    val pages = listOf(
        GuidePage(
            title = "拍之前就告诉你",
            body = "取景时实时分析主体位置、水平、曝光与清晰度，\n" +
                "直接在画面上给出「往右移一点」「相机向左倾斜」这类可执行建议。\n\n" +
                "不是拍完再修，而是按下快门前就拍对。",
            hint = "建议每 0.8 秒才会变一次，不会乱跳",
        ),
        GuidePage(
            title = "参数全给，不设付费墙",
            body = "曝光补偿、ISO、快门、白平衡、手动对焦、变焦、\n" +
                "闪光、网格、画幅、定时、镜像 —— 全部可调。\n\n" +
                "滤镜、边框、水印也全部内置，由算法生成，无需下载素材包。",
            hint = "点取景页右侧的滑杆图标进入参数面板",
        ),
        GuidePage(
            title = "它不联网",
            body = "本应用**不申请网络权限**。\n" +
                "所有分析都在手机端完成，画面与照片不上传、不落盘、不外发。\n\n" +
                "只申请相机一项权限，相册写入由系统相册接口完成。",
            hint = "可在 系统设置 → 应用 → 权限 中核对",
        ),
    )

    val current = pages[page.coerceIn(0, pages.lastIndex)]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShotColors.Black)
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(72.dp))

        Text(
            text = "AI 构图教练",
            color = ShotColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = current.title,
            color = ShotColors.TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = current.body,
            color = ShotColors.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ShotColors.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = current.hint,
                color = ShotColors.TextSecondary,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.weight(1f))

        // 页码指示点
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in pages.indices) {
                Dot(
                    color = if (i == page) ShotColors.Accent else ShotColors.SurfaceHigher,
                    size = if (i == page) 9 else 7,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "跳过",
                color = ShotColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(ShotColors.Surface)
                    .padding(horizontal = 20.dp, vertical = 13.dp)
                    .clickableNoRipple(onSkip),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ShotColors.Accent)
                    .padding(vertical = 14.dp)
                    .clickableNoRipple {
                        if (page < pages.lastIndex) page++ else onStart()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (page < pages.lastIndex) "下一步" else "开始拍摄",
                    color = Color(0xFF1A1A1A),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(34.dp))
    }
}

private data class GuidePage(
    val title: String,
    val body: String,
    val hint: String,
)

/** 无涟漪点击：引导页按钮是实心块，涟漪会破坏干净感 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        androidx.compose.foundation.clickable(
            interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
            indication = null,
            onClick = onClick,
        ),
    )
