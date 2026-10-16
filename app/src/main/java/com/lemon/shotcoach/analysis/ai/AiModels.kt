package com.lemon.shotcoach.analysis.ai

import android.content.Context

/**
 * 端侧 AI 模型清单（路径 1：真神经网络推理，全离线、不上传任何图像）。
 *
 * ## 设计要点
 *
 * 1. **权重不随仓库分发**。模型是第三方预训练权重（各自有许可证），体积 0.2~6.4MB，
 *    放在 `app/src/main/assets/`。跑一次 `tools/fetch-models.sh` 拉下来即可，仓库里只有脚本。
 *
 * 2. **模型缺失不是错误**。所有 AI 通道都先用 [has] 探测；缺失时静默回退传统 CV 实现，
 *    App 照常可跑。这条降级路径是刻意留的（见 docs/ALGORITHMS.md §10），
 *    也让 `./gradlew test` 在没有任何模型 / 没有 Android SDK 的环境下依然成立。
 *
 * 3. **不破坏"离线"承诺**。推理全在本机，`AndroidManifest.xml` 依旧不申请 INTERNET。
 */
object AiModels {

    /** 主体检测：EfficientDet-Lite0（int8，COCO 80 类）。MediaPipe ObjectDetector 加载。 */
    const val SUBJECT_DETECTOR = "efficientdet_lite0.tflite"

    /** 人脸检测：BlazeFace short-range（float16，约 230KB）。MediaPipe FaceDetector 加载。 */
    const val FACE_DETECTOR = "blaze_face_short_range.tflite"

    /** 美感评分：NIMA MobileNet（AVA 数据集），输出 10 桶分布 → 期望分 1..10。 */
    const val AESTHETIC = "nima_aesthetic_fp16.tflite"

    /** 技术质量分：NIMA MobileNet（TID2013）。可选，默认不加载。 */
    const val TECHNICAL = "nima_technical_fp16.tflite"

    /** 全部模型文件名（供校验脚本 / 文档使用） */
    val ALL = listOf(SUBJECT_DETECTOR, FACE_DETECTOR, AESTHETIC, TECHNICAL)

    /** 必须存在的三个模型（TECHNICAL 是可选项） */
    val REQUIRED = listOf(SUBJECT_DETECTOR, FACE_DETECTOR, AESTHETIC)

    /**
     * 模型是否存在于 assets。
     *
     * 每次都打一次 AssetManager，所以调用方应缓存结果（各检测器在构造时探测一次即可）。
     * 用 `runCatching` 而非 `catch (FileNotFoundException)`：不同 ROM 上缺文件抛的异常
     * 类型并不统一（有的抛 IOException），吞掉 Throwable 更稳。
     */
    fun has(context: Context, assetName: String): Boolean =
        runCatching { context.assets.open(assetName).close() }.isSuccess
}
