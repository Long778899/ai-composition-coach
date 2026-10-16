package com.lemon.shotcoach.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.camera.FilterCatalog
import com.lemon.shotcoach.camera.MirrorMode
import com.lemon.shotcoach.filter.FilterEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 成片处理流水线：**镜像 → 滤镜 → 水印 → 边框**。
 *
 * == 顺序不能乱，每步都有理由 ==
 *  1. **镜像最先**：水印文字绝不能被镜像（否则文字是反的）。
 *  2. **滤镜在镜像后**：镜像只是像素重排，先镜后滤不影响结果，但省一次遍历。
 *  3. **水印在滤镜后**：水印是"叠加信息"，不该被胶片滤镜染色/褪色。
 *  4. **边框最后**：边框在照片之外，若先加边框，水印的坐标基准就变了。
 *
 * == 性能 ==
 * 全部是 CPU 运算，12MP 全流程约 1.5~3s。调用方必须放在
 * `Dispatchers.Default` 上执行，并在 UI 上显示进度。
 */
object PhotoProcessor {

    private val labelFormat = SimpleDateFormat("'SC'  yyyy.MM.dd  HH:mm", Locale.US)

    /**
     * @param src      相机输出的原始 Bitmap（已按画幅裁切，未套滤镜）
     * @param isFront  是否前置（配合 [CameraParams.mirrorFront] 决定是否翻转）
     * @param deviceLabel 机型文字，由调用方注入
     */
    fun process(
        src: Bitmap,
        params: CameraParams,
        takenAt: Long,
        deviceLabel: String,
        isFront: Boolean,
    ): Bitmap {
        var bmp = src

        // 1. 镜像
        if (shouldMirror(params.mirrorFront, isFront)) {
            bmp = mirrorHorizontal(bmp)
            if (bmp !== src) src.recycle()
        }

        // 2. 滤镜
        val spec = FilterCatalog.byId(params.filterId)
        bmp = FilterEngine.applyTrilinearToBitmap(bmp, spec, strength = params.filterStrength)

        // 3. 水印（画在照片本体内）
        if (params.watermark.enabled) {
            bmp = bmp.toMutableIfNeeded()
            val canvas = Canvas(bmp)
            WatermarkRenderer.draw(
                canvas = canvas,
                cfg = params.watermark,
                imageWidth = bmp.width,
                imageHeight = bmp.height,
                takenAt = takenAt,
                deviceLabel = deviceLabel,
            )
        }

        // 4. 边框（在最外层）
        val borderSpec = BorderCatalog.byId(params.borderId)
        if (!borderSpec.isNone) {
            val label = if (borderSpec.polaroid || borderSpec.filmStripLabel) {
                labelFormat.format(Date(takenAt))
            } else {
                null
            }
            bmp = BorderRenderer.render(bmp, borderSpec, label)
        }

        return bmp
    }

    fun shouldMirror(mode: MirrorMode, isFront: Boolean): Boolean = when (mode) {
        MirrorMode.AUTO -> isFront
        MirrorMode.ON -> true
        MirrorMode.OFF -> false
    }

    private fun mirrorHorizontal(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun Bitmap.toMutableIfNeeded(): Bitmap =
        if (isMutable) this else copy(Bitmap.Config.ARGB_8888, true)

    /**
     * 按画幅比例居中裁切。
     *
     * CameraX 输出的 4:3 传感器画面要变成 16:9/1:1/3:4 时，
     * 一律用**居中裁切**（不是拉伸）—— 拉伸会让人脸变形，这是硬底线。
     */
    fun cropToAspect(src: Bitmap, targetRatio: Float): Bitmap {
        val w = src.width
        val h = src.height
        val current = w.toFloat() / h
        if (kotlin.math.abs(current - targetRatio) < 0.01f) return src

        val (nw, nh) = if (current > targetRatio) {
            // 太宽 → 裁左右
            (h * targetRatio).toInt() to h
        } else {
            // 太高 → 裁上下
            w to (w / targetRatio).toInt()
        }
        val x = (w - nw) / 2
        val y = (h - nh) / 2
        val cropped = Bitmap.createBitmap(src, x, y, nw, nh)
        if (cropped !== src) src.recycle()
        return cropped
    }
}
