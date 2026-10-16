package com.lemon.shotcoach.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.lemon.shotcoach.camera.WatermarkConfig
import com.lemon.shotcoach.camera.WatermarkPosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 水印绘制 —— **纯代码画文字/图形，不含任何水印图片素材**。
 *
 * 两个必须处理的工程细节：
 *  1. **可读性**：不管照片是亮是暗，白字都可能糊掉。方案是"描边 + 阴影"
 *     双重保险（先画黑色描边再画填充），这也是各家相机 App 的通行做法。
 *  2. **分辨率自适应**：字号按短边比例计算，4K 输出时字不会小到看不见。
 */
object WatermarkRenderer {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dateOnly = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault())

    /**
     * 在 [canvas] 上绘制水印。[imageWidth]/[imageHeight] 是**照片本体**尺寸，
     * 不含边框 —— 边框是后加的，水印应落在照片内。
     *
     * @param deviceLabel 机型文字（由调用方注入，保持本类不依赖 Build）
     * @param takenAt     拍摄时间（毫秒）
     */
    fun draw(
        canvas: Canvas,
        cfg: WatermarkConfig,
        imageWidth: Int,
        imageHeight: Int,
        takenAt: Long,
        deviceLabel: String,
    ) {
        if (!cfg.enabled) return

        val minSide = minOf(imageWidth, imageHeight).toFloat()
        val fontSize = (minSide * cfg.fontSizeRatio).coerceAtLeast(10f)

        // 组装文字行（顺序：自定义 → 机型 → 时间）
        val lines = buildList {
            if (cfg.customText.isNotBlank()) add(cfg.customText.trim())
            if (cfg.showDevice && deviceLabel.isNotBlank()) add(deviceLabel)
            if (cfg.showTime) add(timeFormat.format(Date(takenAt)))
        }
        if (lines.isEmpty()) return

        val lineGap = fontSize * 0.35f
        val lineHeight = fontSize + lineGap

        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = cfg.color
            alpha = (cfg.opacity.coerceIn(0f, 1f) * 255f).toInt()
            textSize = fontSize
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        // 描边：先用黑色描一圈，保证在任何底色上都能读
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = fontSize * 0.10f
            color = Color.BLACK
            alpha = (fill.alpha * 0.75f).toInt()
        }

        val margin = minSide * 0.030f
        val maxTextWidth = lines.maxOf { fill.measureText(it) }
        val blockHeight = lineHeight * lines.size - lineGap

        val left: Float
        val firstBaseline: Float
        when (cfg.position) {
            WatermarkPosition.TOP_LEFT -> {
                left = margin
                firstBaseline = margin + fontSize
            }

            WatermarkPosition.TOP_RIGHT -> {
                left = imageWidth - margin - maxTextWidth
                firstBaseline = margin + fontSize
            }

            WatermarkPosition.BOTTOM_LEFT -> {
                left = margin
                firstBaseline = imageHeight - margin - blockHeight + fontSize
            }

            WatermarkPosition.BOTTOM_RIGHT -> {
                left = imageWidth - margin - maxTextWidth
                firstBaseline = imageHeight - margin - blockHeight + fontSize
            }
        }

        lines.forEachIndexed { i, line ->
            val baseline = firstBaseline + lineHeight * i
            canvas.drawText(line, left, baseline, stroke)
            canvas.drawText(line, left, baseline, fill)
        }
    }

    /** 左上角"极简日期"样式（很多相机 App 的默认角标） */
    fun drawMinimalDate(
        canvas: Canvas,
        imageWidth: Int,
        imageHeight: Int,
        takenAt: Long,
        color: Int = Color.WHITE,
    ) {
        val minSide = minOf(imageWidth, imageHeight).toFloat()
        val fontSize = minSide * 0.042f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            textSize = fontSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val text = dateOnly.format(Date(takenAt))
        val margin = minSide * 0.045f
        val stroke = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = fontSize * 0.14f
            this.color = Color.BLACK
            alpha = 110
        }
        canvas.drawText(text, margin, margin + fontSize, stroke)
        canvas.drawText(text, margin, margin + fontSize, paint)
    }

    /**
     * 底部渐变条 + 文字。用于"电影感"机位信息条，在没有边框时也能撑住画面。
     */
    fun drawGradientFooter(
        canvas: Canvas,
        imageWidth: Int,
        imageHeight: Int,
        text: String,
    ) {
        val h = imageHeight * 0.16f
        val top = imageHeight - h
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, top, 0f, imageHeight.toFloat(),
                intArrayOf(Color.TRANSPARENT, 0x99000000.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, top, imageWidth.toFloat(), imageHeight.toFloat(), paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = minOf(imageWidth, imageHeight) * 0.030f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val margin = minOf(imageWidth, imageHeight) * 0.032f
        canvas.drawText(text, margin, imageHeight - margin, textPaint)
    }
}
