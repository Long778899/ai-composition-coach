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
import com.lemon.shotcoach.camera.GridStyle
import com.lemon.shotcoach.model.AiChannel
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
            if (state.gridEnabled) {
                when (state.gridStyle) {
                    GridStyle.OFF -> Unit
                    GridStyle.THIRDS -> {
                        for (f in listOf(1f / 3f, 2f / 3f)) {
                            drawLine(gridColor, Offset(w * f, 0f), Offset(w * f, h), stroke)
                            drawLine(gridColor, Offset(0f, h * f), Offset(w, h * f), stroke)
                        }
                    }
                    GridStyle.GOLDEN -> {
                        for (f in listOf(0.382f, 0.618f)) {
                            drawLine(gridColor, Offset(w * f, 0f), Offset(w * f, h), stroke)
                            drawLine(gridColor, Offset(0f, h * f), Offset(w, h * f), stroke)
                        }
                    }
                    GridStyle.CENTER -> {
                        drawLine(gridColor, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
                        drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
                    }
                    GridStyle.DIAGONAL -> {
                        // 对角线构图参考：角到角 + 中心十字（弱化，避免压过主体）
                        val diag = gridColor.copy(alpha = 0.18f)
                        drawLine(diag, Offset(0f, 0f), Offset(w, h), stroke)
                        drawLine(diag, Offset(w, 0f), Offset(0f, h), stroke)
                        drawLine(gridColor, Offset(w / 2f, 0f), Offset(w / 2f, h), stroke)
                        drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), stroke)
                    }
                }
            }

            // ── 水平基准线：|roll| < 1° 变绿 ────────────────
            if (state.levelEnabled) {
                val level = abs(state.rollDegrees) < 1f
                val levelColor = if (level) Color(0xFF4CD964) else Color(0xFFFFD54F)
                drawLine(
                    color = levelColor.copy(alpha = 0.9f),
                    start = Offset(0f, h / 2f),
                    end = Offset(w, h / 2f),
                    strokeWidth = if (level) 3.dp.toPx() else 2.dp.toPx(),
                )
            }

            val guide = state.guideEnabled

            // ── 人脸框 ───────────────────────────────────
            if (guide) {
                for (f in state.faceRects) {
                    drawRect(
                        color = Color(0xFF4CD964).copy(alpha = 0.85f),
                        topLeft = Offset(f.left * w, f.top * h),
                        size = Size(f.width * w, f.height * h),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            // ── 主体指示（端侧 AI 检测框优先，否则退回显著性质心）──
            if (guide) {
                val box = state.subjectBox
                if (box != null) {
                    // 神经网络**测**出来的主体框，画成相机对焦框的样子（四角标记，不用实心方框）
                    val tl = Offset(box.left * w, box.top * h)
                    val sz = Size(box.width * w, box.height * h)
                    val aiColor = Color(0xFF64B5F6)
                    drawRect(
                        color = aiColor.copy(alpha = 0.55f),
                        topLeft = tl,
                        size = sz,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawSubjectCorners(tl, sz, aiColor.copy(alpha = 0.95f))
                } else state.subjectPoint?.let { p ->
                    // 无 AI 时的旧路径：显著性"猜"出来的质心
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
            }

            // ── 方向箭头 ─────────────────────────────────
            if (guide) state.hint?.let { h2 ->
                if (h2.direction != Hint.Direction.NONE && hintAlpha > 0.01f) {
                    drawDirectionArrow(h2.direction, w, h, hintAlpha)
                }
            }
        }

        // ── 提示文案 ─────────────────────────────────────
        if (state.guideEnabled) state.hint?.let { h2 ->
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

        // ── AI 角标：证明本帧确实跑了端侧神经网络（非装饰）────
        if (state.guideEnabled && state.aiChannels.isNotEmpty()) {
            Text(
                text = "AI",
                color = Color(0xFF4CD964),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // ── 评分 HUD（默认关，设置里可开）─────────────────
        if (state.scoreEnabled) state.score?.let { s ->
            val aesthetic = s.aestheticScore
            val aiLine = if (state.aiChannels.isEmpty()) {
                ""
            } else {
                "\nAI: " + state.aiChannels.joinToString("+") { it.label() }
            }
            Text(
                text = "分 ${s.total} · 主 ${s.subjectScore} 平 ${s.levelScore} " +
                    "曝 ${s.exposureScore} 清 ${s.sharpnessScore}" +
                    (aesthetic?.let { "\n美感 $it (NIMA)" } ?: "") +
                    "\n%.1f fps · %.1f ms".format(state.fps, state.latencyMs) +
                    aiLine,
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

/**
 * 画主体检测框的"四角标记"（相机对焦框那种）。
 *
 * 不用实心方框的原因：端侧 AI 的框会随帧小幅抖动，实心框看着像画面被切了一刀；
 * 四角标记视觉重量低，且天然传达"这是检测结果"而不是"这是裁剪区"。
 */
private fun DrawScope.drawSubjectCorners(topLeft: Offset, size: Size, color: Color) {
    val l = (minOf(size.width, size.height) * 0.22f)
        .coerceIn(6.dp.toPx(), 26.dp.toPx())
    val stroke = 2.dp.toPx()
    val x0 = topLeft.x
    val y0 = topLeft.y
    val x1 = topLeft.x + size.width
    val y1 = topLeft.y + size.height

    drawLine(color, Offset(x0, y0), Offset(x0 + l, y0), stroke)   // 左上横
    drawLine(color, Offset(x0, y0), Offset(x0, y0 + l), stroke)   // 左上竖
    drawLine(color, Offset(x1, y0), Offset(x1 - l, y0), stroke)   // 右上横
    drawLine(color, Offset(x1, y0), Offset(x1, y0 + l), stroke)   // 右上竖
    drawLine(color, Offset(x0, y1), Offset(x0 + l, y1), stroke)   // 左下横
    drawLine(color, Offset(x0, y1), Offset(x0, y1 - l), stroke)   // 左下竖
    drawLine(color, Offset(x1, y1), Offset(x1 - l, y1), stroke)   // 右下横
    drawLine(color, Offset(x1, y1), Offset(x1, y1 - l), stroke)   // 右下竖
}

/** 端侧 AI 通道的中文名（评分 HUD 用） */
private fun AiChannel.label(): String = when (this) {
    AiChannel.SUBJECT -> "主体"
    AiChannel.FACE -> "人脸"
    AiChannel.AESTHETIC -> "美感"
}
