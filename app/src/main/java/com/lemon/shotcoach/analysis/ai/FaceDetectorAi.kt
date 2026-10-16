package com.lemon.shotcoach.analysis.ai

import android.content.Context
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.lemon.shotcoach.model.NormalizedRect

/**
 * 人脸检测（FR-7 的 AI 实现）。BlazeFace short-range（float16，约 230KB），端侧神经网络。
 *
 * ## 为什么原来的人脸通道是死的
 *
 * 旧实现有两条路：
 *  - **ML Kit**：`app/build.gradle.kts` 里那行依赖被注释掉了 → 永远不可用；
 *  - **Haar 级联**：`assets/` 里没有 `haarcascade_frontalface_alt.xml` → 永远不可用。
 *
 * 结果是 `cachedFaces` 恒为空表，`R_FACE_SMALL`（priority 60）与 `R_FACE_NONE`（priority 40）
 * 两条规则**永不触发** —— 7 条内置规则实际只有 5 条活着。
 *
 * 现在换成 MediaPipe BlazeFace：模型随 APK 打包（走 `tools/fetch-models.sh`），
 * 不依赖 Play 服务，冷启动后即可用。
 *
 * 与 [com.lemon.shotcoach.analysis.analyzer.FaceAnalyzer] 的契约一致：
 * 返回**按面积降序**的归一化人脸框；不可用时返回空表。
 */
class FaceDetectorAi(
    context: Context,
    private val delegate: Delegate = Delegate.CPU,
) : AutoCloseable {

    private val detector: FaceDetector? = create(context, delegate)

    val available: Boolean get() = detector != null

    /** @return 归一化人脸框，按面积降序（[FrameSignals.faces] 的既有约定） */
    fun detect(image: MPImage, width: Int, height: Int): List<NormalizedRect> {
        val d = detector ?: return emptyList()
        if (width <= 0 || height <= 0) return emptyList()
        return runCatching {
            d.detect(image).detections()
                .mapNotNull { det ->
                    val b = det.boundingBox() ?: return@mapNotNull null
                    NormalizedRect(
                        left = (b.left / width).coerceIn(0f, 1f),
                        top = (b.top / height).coerceIn(0f, 1f),
                        right = (b.right / width).coerceIn(0f, 1f),
                        bottom = (b.bottom / height).coerceIn(0f, 1f),
                    )
                }
                .filter { it.area > MIN_AREA }
                .sortedByDescending { it.area }
        }.getOrElse { emptyList() }
    }

    override fun close() {
        runCatching { detector?.close() }
    }

    companion object {
        const val MIN_DETECTION_CONFIDENCE = 0.5f
        const val MIN_SUPPRESSION_THRESHOLD = 0.3f

        /** 丢掉面积占比 < 0.5% 的框：远景里的路人脸不该干扰构图判断 */
        const val MIN_AREA = 0.005f

        private fun create(context: Context, delegate: Delegate): FaceDetector? = try {
            if (!AiModels.has(context, AiModels.FACE_DETECTOR)) {
                null
            } else {
                val base = BaseOptions.builder()
                    .setModelAssetPath(AiModels.FACE_DETECTOR)
                    .setDelegate(delegate)
                    .build()
                val options = FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                    .setMinSuppressionThreshold(MIN_SUPPRESSION_THRESHOLD)
                    .build()
                FaceDetector.createFromOptions(context, options)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
