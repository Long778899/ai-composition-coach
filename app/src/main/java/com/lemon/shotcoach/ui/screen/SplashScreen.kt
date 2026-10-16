package com.lemon.shotcoach.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.ui.theme.ShotColors
import kotlinx.coroutines.delay

/**
 * 启动页。
 *
 * 光圈图形是**画出来的**（6 片叶片 + 外环 + 中心十字），没有任何图片资源 ——
 * 与全项目"零素材文件"的做法一致。旋转动画让首屏不至于死板。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (visible) 60f else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "aperture",
    )
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "fade",
    )

    LaunchedEffect(Unit) {
        visible = true
        delay(1100)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ShotColors.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(modifier = Modifier.size(112.dp)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val outer = size.minDimension / 2f - 2.dp.toPx()
                val ring = 2.dp.toPx()

                // 外环
                drawCircle(
                    color = ShotColors.Accent.copy(alpha = 0.9f * fade),
                    radius = outer,
                    center = Offset(cx, cy),
                    style = Stroke(width = ring),
                )
                // 内环
                drawCircle(
                    color = ShotColors.Accent.copy(alpha = 0.35f * fade),
                    radius = outer * 0.62f,
                    center = Offset(cx, cy),
                    style = Stroke(width = ring),
                )

                // 6 片光圈叶片：两条边构成一个"<"，绕中心旋转 6 次
                val blades = 6
                val bladeR = outer * 0.88f
                val bladeHalf = outer * 0.30f
                for (i in 0 until blades) {
                    val a = Math.toRadians((i * 360f / blades + rotation).toDouble())
                    val ax = cx + (bladeR * Math.cos(a)).toFloat()
                    val ay = cy + (bladeR * Math.sin(a)).toFloat()

                    val a1 = a + Math.toRadians(62.0)
                    val a2 = a - Math.toRadians(62.0)
                    drawLine(
                        color = ShotColors.Accent.copy(alpha = 0.75f * fade),
                        start = Offset(ax, ay),
                        end = Offset(
                            (ax + bladeHalf * Math.cos(a1)).toFloat(),
                            (ay + bladeHalf * Math.sin(a1)).toFloat(),
                        ),
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawLine(
                        color = ShotColors.Accent.copy(alpha = 0.75f * fade),
                        start = Offset(ax, ay),
                        end = Offset(
                            (ax + bladeHalf * Math.cos(a2)).toFloat(),
                            (ay + bladeHalf * Math.sin(a2)).toFloat(),
                        ),
                        strokeWidth = 2.dp.toPx(),
                    )
                }

                // 中心十字（对焦感）
                drawLine(
                    color = Color.White.copy(alpha = 0.8f * fade),
                    start = Offset(cx - 8.dp.toPx(), cy),
                    end = Offset(cx + 8.dp.toPx(), cy),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.8f * fade),
                    start = Offset(cx, cy - 8.dp.toPx()),
                    end = Offset(cx, cy + 8.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }

            Text(
                text = "AI 构图教练",
                color = Color.White.copy(alpha = fade),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "实时构图引导 · 全参数相机 · 端侧零联网",
                color = ShotColors.TextSecondary.copy(alpha = fade),
                fontSize = 12.sp,
            )
        }
    }
}
