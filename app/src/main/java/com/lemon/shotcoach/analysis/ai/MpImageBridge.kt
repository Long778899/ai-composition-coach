package com.lemon.shotcoach.analysis.ai

import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `Mat(CV_8UC4)` → MediaPipe `MPImage` 的**零大块分配**桥。
 *
 * ## 为什么不用 Bitmap 路径
 *
 * MediaPipe 同时提供 `BitmapImageBuilder` 和 `ByteBufferImageBuilder`。前者看着更顺手
 * （OpenCV 有现成的 `Utils.matToBitmap`），但它有一个致命细节：
 *
 * ```java
 * // com.google.mediapipe.framework.image.BitmapImageContainer#close()
 * public void close() { bitmap.recycle(); }
 * ```
 *
 * 也就是说 `MPImage.close()` 会**回收你传进去的 Bitmap**。而本工程为了满足 NFR-2
 * （分析路径零每帧分配）必须复用同一个 Bitmap —— 一旦被 recycle，下一帧就是
 * `RuntimeException: Canvas: trying to use a recycled bitmap`。
 *
 * `ByteBufferImageContainer#close()` 则是空实现（已用 `javap -c` 核实），
 * 缓冲区由我们自己持有、反复覆写，因此走字节缓冲路径。
 *
 * ## 内存布局
 *
 * `Bitmap.Config.ARGB_8888` 在内存里其实是 RGBA 字节序，OpenCV 的 `CV_8UC4` 也是 RGBA，
 * 两边一致 —— 所以拷出去的像素不需要任何通道重排。
 * 注意 NIMA 那边要的是 RGB，得在填浮点缓冲区时跳过 A（见 [AestheticScorer]）。
 *
 * 非线程安全：由分析线程独占。
 */
class MpImageBridge {

    private var buffer: ByteBuffer? = null
    private var scratch = ByteArray(0)
    private var width = 0
    private var height = 0

    /** 当前复用缓冲区的容量（字节），供文档/测试断言 */
    val capacityBytes: Int get() = buffer?.capacity() ?: 0

    /**
     * 把一幅 RGBA 图打包成 [MPImage]。
     *
     * @param rgba 必须是连续的 `CV_8UC4`（`AiPipeline` 里的等比缩放结果就满足）
     * @return 在**同一帧内**有效的 MPImage，用完必须 `close()`
     */
    fun build(rgba: Mat): MPImage {
        require(rgba.type() == CvType.CV_8UC4) { "AI 输入必须是 CV_8UC4(RGBA)，实际 type=${rgba.type()}" }
        require(rgba.isContinuous) { "AI 输入 Mat 必须连续（不能是 ROI 视图）" }

        val w = rgba.cols()
        val h = rgba.rows()
        require(w > 0 && h > 0) { "AI 输入尺寸非法：${w}x${h}" }

        var buf = buffer
        if (buf == null || width != w || height != h) {
            buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            buffer = buf
            width = w
            height = h
        }

        // OpenCV Java 的 Mat **不暴露**底层 ByteBuffer（只有 dataAddr() 这个原生指针），
        // 所以只能用 get(row, col, byte[]) 批量拷出像素。scratch 数组跨帧复用，
        // 稳态下仍然零分配 —— 只是多一次 w*h*4 的内存拷贝（320² 约 0.4MB，可忽略）。
        val need = w * h * 4
        if (scratch.size != need) scratch = ByteArray(need)
        rgba.get(0, 0, scratch)

        buf.clear()
        buf.put(scratch)
        buf.rewind()

        return ByteBufferImageBuilder(buf, w, h, MPImage.IMAGE_FORMAT_RGBA).build()
    }

    fun release() {
        buffer = null
        scratch = ByteArray(0)
        width = 0
        height = 0
    }
}
