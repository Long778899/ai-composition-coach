package com.lemon.shotcoach.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 边框渲染器 —— **全部用 Canvas 画**，零素材图片。
 *
 * == 为什么不用素材图 ==
 * 素材边框是位图：1080p 的边框套到 4K 输出上会被拉伸模糊；换分辨率要出多套。
 * 用代码画则天然是矢量的 —— 边框宽度、齿孔数量、字号全按短边比例算，
 * 任何分辨率都锐利。代价是每个样式要写几十行绘制逻辑（值得）。
 */
object BorderRenderer {

    /**
     * 给 [src] 套上边框。返回**新 Bitmap**；[spec] 为 [BorderKind.NONE] 时原样返回。
     *
     * @param label 胶片边/宝丽来底部字条的文字（如机型 + 日期），null 则不画
     */
    fun render(src: Bitmap, spec: BorderSpec, label: String? = null, labelColor: Int = 0xFFFFC24B.toInt()): Bitmap {
        if (spec.isNone) return src

        val w = src.width
        val h = src.height
        val short = minOf(w, h).toFloat()
        val pad = (short * spec.widthRatio).roundToInt().coerceAtLeast(2)

        return when (spec.kind) {
            BorderKind.NONE -> src
            BorderKind.SOLID -> renderSolid(src, spec, pad)
            BorderKind.DOUBLE_LINE -> renderDoubleLine(src, spec, pad)
            BorderKind.ROUNDED -> renderRounded(src, spec, pad, short)
            BorderKind.POLAROID -> renderPolaroid(src, spec, pad, short, label, labelColor)
            BorderKind.FILM -> renderFilm(src, spec, pad, short, label, labelColor)
            BorderKind.VIGNETTE_FRAME -> renderVignetteFrame(src, spec, pad, short)
        }
    }

    /** 边框会把图放大的尺寸（UI 预览与进度估算用） */
    fun expandedSize(w: Int, h: Int, spec: BorderSpec): Pair<Int, Int> {
        if (spec.isNone) return w to h
        val short = minOf(w, h).toFloat()
        val pad = (short * spec.widthRatio).roundToInt()
        val inset = (short * spec.insetRatio).roundToInt()
        return when (spec.kind) {
            BorderKind.POLAROID -> (w + pad * 2) to (h + pad + (pad * 3.6f).roundToInt())
            BorderKind.FILM -> (w + pad) to (h + pad * 2)
            else -> (w + (pad + inset) * 2) to (h + (pad + inset) * 2)
        }
    }

    // ── 各样式 ────────────────────────────────────────────────

    private fun renderSolid(src: Bitmap, spec: BorderSpec, pad: Int): Bitmap {
        val inset = (minOf(src.width, src.height) * spec.insetRatio).roundToInt()
        val total = pad + inset
        val out = Bitmap.createBitmap(src.width + total * 2, src.height + total * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(spec.color)
        c.drawBitmap(src, total.toFloat(), total.toFloat(), null)
        return out
    }

    private fun renderDoubleLine(src: Bitmap, spec: BorderSpec, pad: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width + pad * 2, src.height + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(spec.color2)
        c.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)

        val photo = RectF(pad.toFloat(), pad.toFloat(), (pad + src.width).toFloat(), (pad + src.height).toFloat())

        // 外粗线
        val outerInset = pad * 0.30f
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, pad * 0.16f)
            color = spec.color
        }
        c.drawRect(
            RectF(
                photo.left - pad + outerInset, photo.top - pad + outerInset,
                photo.right + pad - outerInset, photo.bottom + pad - outerInset,
            ),
            p1,
        )

        // 内细线（紧贴照片）
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, pad * 0.07f)
            color = spec.color
          }
        val inflate = pad * 0.16f
        c.drawRect(
            RectF(photo.left - inflate, photo.top - inflate, photo.right + inflate, photo.bottom + inflate),
            p2,
        )
        return out
    }

    private fun renderRounded(src: Bitmap, spec: BorderSpec, pad: Int, short: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(spec.color)

        val radius = short * spec.cornerRatio
        val inner = RectF(pad.toFloat(), pad.toFloat(), (src.width - pad).toFloat(), (src.height - pad).toFloat())
        val innerPath = android.graphics.Path().apply {
            addRoundRect(inner, radius, radius, android.graphics.Path.Direction.CW)
        }
        c.save()
        c.clipPath(innerPath)
        c.drawBitmap(src, null, inner, null)
        c.restore()

        // 外沿再描一圈同色，压住裁切锯齿
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, pad * 0.5f)
            color = spec.color
        }
        c.drawRoundRect(inner, radius, radius, stroke)
        return out
    }

    private fun renderPolaroid(
        src: Bitmap,
        spec: BorderSpec,
        pad: Int,
        short: Float,
        label: String?,
        labelColor: Int,
    ): Bitmap {
        val bottom = (pad * 3.6f).roundToInt()
        val out = Bitmap.createBitmap(src.width + pad * 2, src.height + pad + bottom, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(spec.color)

        // 照片内缩一点点，露出一点底色更自然
        val inner = pad * 0.18f
        c.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        c.drawRect(
            RectF(pad - inner, pad - inner, (pad + src.width + inner).toFloat(), (pad + src.height + inner).toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = inner
                color = 0x22000000
            },
        )

        if (!label.isNullOrBlank()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = labelColor
                textSize = short * 0.026f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(label, (src.width + pad * 2) / 2f, src.height + pad + bottom * 0.62f, p)
        }
        return out
    }

    private fun renderFilm(
        src: Bitmap,
        spec: BorderSpec,
        pad: Int,
        short: Float,
        label: String?,
        labelColor: Int,
    ): Bitmap {
        val band = pad                    // 上下黑边
        val side = (pad * 0.35f).roundToInt().coerceAtLeast(1)  // 左右窄边
        val out = Bitmap.createBitmap(src.width + side * 2, src.height + band * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(spec.color)
        c.drawBitmap(src, side.toFloat(), band.toFloat(), null)

        // 齿孔：沿上下两条黑边均匀排布
        val holeWidth = band * 0.52f
        val holeHeight = band * 0.34f
        val holeRadius = holeHeight * 0.30f
        val spacing = holeWidth * 1.85f
        val count = ((out.width - spacing) / spacing).toInt().coerceAtLeast(1)
        val startX = (out.width - spacing * count) / 2f + spacing * 0.5f - holeWidth * 0.5f
        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDED.toInt() }

        for (i in 0..count) {
            val x = startX + spacing * i
            c.drawRoundRect(
                RectF(x, band * 0.30f, x + holeWidth, band * 0.30f + holeHeight),
                holeRadius, holeRadius, holePaint,
            )
            c.drawRoundRect(
                RectF(x, out.height - band * 0.30f - holeHeight, x + holeWidth, out.height - band * 0.30f),
                holeRadius, holeRadius, holePaint,
            )
        }

        // 底部小字条（琥珀色，模拟胶片曝光信息）
        if (!label.isNullOrBlank()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = labelColor
                textSize = band * 0.30f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            }
            val textY = out.height - band * 0.52f
            val margin = band * 0.35f
            c.drawText(label, side + margin, textY + p.textSize * 0.35f, p)
            // 右端小方块（胶卷序号位）
            val boxSize = band * 0.26f
            c.drawRect(
                RectF(out.width - side - margin - boxSize, textY - boxSize * 0.8f, out.width - side - margin, textY + boxSize * 0.2f),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = labelColor },
            )
        }
        return out
    }

    private fun renderVignetteFrame(src: Bitmap, spec: BorderSpec, pad: Int, short: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)

        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val depth = (short * spec.widthRatio).coerceAtLeast(8f)
        val dark = 0x88000000.toInt()

        // 四条边各铺一层线性渐变，交汇处形成柔和的暗角框
        val top = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, depth, dark, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, depth, top)

        val bottom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, h, 0f, h - depth, dark, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, h - depth, w, h, bottom)

        val left = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, depth, 0f, dark, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, depth, h, left)

        val right = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(w, 0f, w - depth, 0f, dark, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawRect(w - depth, 0f, w, h, right)

        // 最外一圈极细白线，收紧画面
        c.drawRect(
            RectF(1f, 1f, w - 1f, h - 1f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1f, short * 0.0035f)
                color = 0x66FFFFFF
            },
        )
        return out
    }
}
