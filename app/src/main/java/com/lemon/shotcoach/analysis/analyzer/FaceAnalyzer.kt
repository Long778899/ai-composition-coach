package com.lemon.shotcoach.analysis.analyzer

import android.content.Context
import com.lemon.shotcoach.model.NormalizedRect
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 人脸位置检测（FR-7）。慢通道，每 2 帧，预算 3~8ms。
 *
 * 两条实现路径（见 docs/ALGORITHMS.md §5）：
 *  - ML Kit Face Detection（首选，端侧、约 5ms、随 Play 服务分发、**不打包模型文件**）
 *  - OpenCV Haar Cascade（回退，级联文件约 900KB 随 APK 附带）
 *
 * ⚠️ 隐私：检测结果**仅用于构图引导**，不保存、不上传、不做身份识别。
 */
class FaceAnalyzer(
    context: Context,
    private val useMlKit: Boolean = true,
    @Suppress("unused") private val mlKitDetector: Any? = null, // 见 README：启用 ML Kit 依赖后替换为 FaceDetector
) {
    val cadence = 2

    /** ML Kit 的异步结果缓存（ML Kit 是 Task 异步 API，分析线程同步读取最新值） */
    private val mlKitCache = AtomicReference<List<NormalizedRect>>(emptyList())

    private val cascade: CascadeClassifier? = if (useMlKit && mlKitDetector != null) null else loadCascade(context)
    private val rects = MatOfRect()

    /**
     * @param gray 灰度图（建议 320×240，人脸小需要略高分辨率）
     * @param scale 若 gray 是 letterbox 过的，需要此比例把坐标映射回原画面
     */
    fun analyze(gray: Mat, scale: Float = 1f): List<NormalizedRect> {
        if (mlKitDetector != null) {
            // 生产实现：见 README「启用 ML Kit」段落，把 InputImage 交给 detector 并写入 mlKitCache
            return mlKitCache.get()
        }

        val c = cascade ?: return emptyList()
        c.detectMultiScale(
            gray, rects,
            1.1,        // scaleFactor
            3,          // minNeighbors
            0,          // flags
            Size(24.0, 24.0),  // minSize
            Size(),
        )

        val out = ArrayList<NormalizedRect>(rects.toArray().size)
        for (r in rects.toArray()) {
            out += toNormalized(r, gray.cols(), gray.rows(), scale)
        }
        // 按面积降序：最大人脸 = 主体
        out.sortByDescending { it.area }
        rects.release()
        return out
    }

    private fun toNormalized(r: Rect, w: Int, h: Int, scale: Float): NormalizedRect {
        // 反向映射：gray 是缩放后的图，除 scale 还原到归一化空间
        val f = 1f / scale
        return NormalizedRect(
            left = (r.x * f / w).coerceIn(0f, 1f),
            top = (r.y * f / h).coerceIn(0f, 1f),
            right = ((r.x + r.width) * f / w).coerceIn(0f, 1f),
            bottom = ((r.y + r.height) * f / h).coerceIn(0f, 1f),
        )
    }

    /** 把 assets 里的级联文件复制到 filesDir（OpenCV 无法直接读 assets） */
    private fun loadCascade(context: Context): CascadeClassifier? {
        return try {
            val target = File(context.filesDir, "haarcascade_frontalface_default.xml")
            if (!target.exists()) {
                context.assets.open("haarcascade_frontalface_default.xml").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            val cc = CascadeClassifier(target.absolutePath)
            if (cc.empty()) null else cc
        } catch (_: Throwable) {
            null
        }
    }

    /** 供 ML Kit 路径的回调写入 */
    fun updateMlKitCache(rects: List<NormalizedRect>) = mlKitCache.set(rects)

    companion object {
        /** 灰度图的目标尺寸（人脸通道用更高分辨率） */
        fun targetGray(src: Mat): Mat {
            val dst = Mat()
            Imgproc.resize(src, dst, Size(320.0, 240.0), 0.0, 0.0, Imgproc.INTER_AREA)
            return dst
        }
    }
}
