package com.lemon.shotcoach.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.model.Hint
import com.lemon.shotcoach.model.UiState
import kotlin.math.abs

/**
 * 取景叠加层（FR-12）。
 *
 * 纯绘制，不做任何分析 —— 分析结果通过 [state] 传入。
 * 见 docs/ARCHITECTURE.md §1「UI 层只消费 StateFlow<UiState>」。
 */
@Composable
fun OverlayView(
    state: UiState,
    modifier: Modifier = Modifier,
) {
    // 提示切换动画：文字淡入
    val hintAlpha by animateFloatAsState(
        targetValue = if (state.hint != null) 1f else 0f,
        label = "hintAlpha",
    )

    Box(modifier = modifier.fillMaxSize()) {

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // ── 网格线 ────────────────────────────────────
            val gridColor = Color.White.copy(alpha = 0.28f)
            val stroke = 1.dp.toPx()
            when (state.gridStyle) {
                UiState.GridStyle.THIRDS -> {
                    for (f in listOf(1f / 3f, 2f / 3f)) {
                        drawLine(gridColor, Offset(w * f, 0f), Offset(w * f, h), stroke)
                        drawLine(gridColor, Offset(0f, h * f), Offset(w, h * f), stroke)
                    }
                }
                UiState.GridStyle.GOLDEN -> {
                    for (f in listOf(0.382f, 0.618f)) {
                        drawLine(gridColor, Offset(w * f, 0f), Offset(w * f, h), stroke)
                        drawLine(gridColor, Offset(0f, h * f), Offset(w, h * f), stroke)
                    }
                }
                UiState.GridStyle.CROSS -> {
                    drawLine(gridColor, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
                    drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
                }
            }

            // ── 水平基准线：|roll| < 1° 变绿 ────────────────
            val level = abs(state.rollDegrees) < 1f
            val levelColor = if (level) Color(0xFF4CD964) else Color(0xFFFFD54F)
            drawLine(
                color = levelColor.copy(alpha = 0.9f),
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = if (level) 3.dp.toPx() else 2.dp.toPx(),
            )

            // ── 人脸框 ───────────────────────────────────
            for (f in state.faceRects) {
                drawRect(
                    color = Color(0xFF4CD964).copy(alpha = 0.85f),
                    topLeft = Offset(f.left * w, f.top * h),
                    size = Size(f.width * w, f.height * h),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            // ── 主体指示框（显著性定位）────────────────────
            state.subjectPoint?.let { p ->
                val cx = p.x * w
                val cy = p.y * h
                drawCircle(
                    color = Color(0xFF64B5F6).copy(alpha = 0.9f),
                    radius = 26.dp.toPx(),
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawCircle(
                    color = Color(0xFF64B5F6).copy(alpha = 0.5f),
                    radius = 3.dp.toPx(),
                    center = Offset(cx, cy),
                )
            }

            // ── 方向箭头 ─────────────────────────────────
            state.hint?.let { h2 ->
                if (h2.direction != Hint.Direction.NONE && hintAlpha > 0.01f) {
                    drawDirectionArrow(h2.direction, w, h, hintAlpha)
                }
            }
        }

        // ── 提示文案 ─────────────────────────────────────
        state.hint?.let { h2 ->
            Text(
                text = h2.text,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp)
                    .background(Color.Black.copy(alpha = 0.55f * hintAlpha))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ── 开发者 HUD ───────────────────────────────────
        state.score?.let { s ->
            Text(
                text = "分 ${s.total} · 主 ${s.subjectScore} 平 ${s.levelScore} " +
                    "曝 ${s.exposureScore} 清 ${s.sharpnessScore}\n" +
                    "%.1f fps · %.1f ms".format(state.fps, state.latencyMs),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(6.dp),
            )
        }
    }
}

/** 在画面边缘绘制指向提示方向的箭头 */
private fun DrawScope.drawDirectionArrow(
    dir: Hint.Direction,
    w: Float,
    h: Float,
    alpha: Float,
) {
    val len = 46.dp.toPx()
    val half = 14.dp.toPx()
    val margin = 34.dp.toPx()
    val color = Color(0xFFFFD54F).copy(alpha = 0.9f * alpha)

    val tip: Offset
    val base: Offset
    when (dir) {
        Hint.Direction.LEFT -> { tip = Offset(margin, h / 2f); base = Offset(margin + len, h / 2f) }
        Hint.Direction.RIGHT -> { tip = Offset(w - margin, h / 2f); base = Offset(w - margin - len, h / 2f) }
        Hint.Direction.UP -> { tip = Offset(w / 2f, margin); base = Offset(w / 2f, margin + len) }
        Hint.Direction.DOWN -> { tip = Offset(w / 2f, h - margin); base = Offset(w / 2f, h - margin - len) }
        Hint.Direction.NONE -> return
    }

    val path = Path().apply {
        moveTo(tip.x, tip.y)
        when (dir) {
            Hint.Direction.LEFT, Hint.Direction.RIGHT -> {
                val sx = if (dir == Hint.Direction.LEFT) 1f else -1f
                lineTo(base.x + sx * half, base.y - half)
                moveTo(tip.x, tip.y)
                lineTo(base.x + sx * half, base.y + half)
            }
            Hint.Direction.UP, Hint.Direction.DOWN -> {
                val sy = if (dir == Hint.Direction.UP) 1f else -1f
                lineTo(base.x - half, base.y + sy * half)
                moveTo(tip.x, tip.y)
                lineTo(base.x + half, base.y + sy * half)
            }
            Hint.Direction.NONE -> Unit
        }
    }
    drawPath(path, color, style = Stroke(width = 4.dp.toPx()))
}
