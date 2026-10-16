package com.lemon.shotcoach.analysis.ai

import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 美感 / 技术质量评分（NIMA，端侧神经网络）。这是全工程**第一处真正的"AI 打分"**。
 *
 * ## 模型
 *
 * NIMA（Neural Image Assessment，Google 2017）用 MobileNet 骨干 + 10 桶 softmax 头，
 * 在 AVA 数据集（25 万张图 × 人类美学评分）上训练。输出不是"好/坏"标签，而是**分数的概率分布**，
 * 期望分 `Σ (i+1)·pᵢ` 就是 1..10 的美学评分 —— 与人类评分的相关性（SRCC）约 0.5~0.6。
 *
 * 输入 `[1,224,224,3]` float32，NHWC，**RGB 顺序**，归一化 `x/127.5 - 1` → `[-1,1]`。
 *
 * ## 为什么它比手调加权公式值得信
 *
 * 旧 `CompositionScorer` 的构图分是"主体到最近三分点的归一化距离 × 400"—— 一条人为线性公式，
 * 没有依据、也无法表达"这张照片就是不好看"。NIMA 是**从 25 万人评标签里学出来的**：
 * 背景杂乱、光线脏、主体割裂这些说不清楚的东西，模型能给出分数。
 *
 * ## 预处理
 *
 * 短边缩到 224 + 中心裁剪 —— 贴近 NIMA 训练时的做法；直接拉伸到 224×224 会改变纵横比。
 *
 * 非线程安全：由分析线程独占。
 */
class AestheticScorer(
    context: Context,
    which: Which = Which.AESTHETIC,
) : AutoCloseable {

    enum class Which(val asset: String) {
        /** AVA 数据集训练，评的是"拍得美不美" */
        AESTHETIC(AiModels.AESTHETIC),

        /** TID2013 数据集训练，评的是"技术质量"（噪声/模糊/压缩失真） */
        TECHNICAL(AiModels.TECHNICAL),
    }

    /** @param score 期望分 1..10；@param distribution 10 桶概率（调试/可视化用） */
    data class Result(val score: Float, val distribution: FloatArray) {
        override fun equals(other: Any?): Boolean =
            other is Result && score == other.score && distribution.contentEquals(other.distribution)

        override fun hashCode(): Int = 31 * score.hashCode() + distribution.contentHashCode()
    }

    private val interpreter: Interpreter?
    private val ready: Boolean

    // ── 复用的中间缓冲区（NFR-2：稳态零分配）──
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(SIDE * SIDE * 3 * FLOAT_BYTES).order(ByteOrder.nativeOrder())
    private val inputFloats = inputBuffer.asFloatBuffer()
    private val output = Array(1) { FloatArray(BINS) }
    private val scaled = Mat()
    private val cropped = Mat()
    private val rgbaBytes = ByteArray(SIDE * SIDE * 4)

    init {
        var made: Interpreter? = null
        var ok = false
        try {
            val model = loadAsset(context, which.asset)
            if (model != null) {
                made = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
                val inTensor = made.getInputTensor(0)
                val outTensor = made.getOutputTensor(0)
                // 只接受 float32 进 / float32 出、10 桶的变体；其它类型直接降级为不可用，
                // 而不是在推理时崩掉。量化变体要另写量化预处理。
                ok = inTensor.dataType() == DataType.FLOAT32 &&
                    outTensor.dataType() == DataType.FLOAT32 &&
                    outTensor.numElements() == BINS
            }
        } catch (_: Throwable) {
            ok = false
        }
        interpreter = made
        ready = ok
    }

    val available: Boolean get() = ready

    /**
     * @param rgba 整帧 RGBA（`CV_8UC4`）。预处理在本类内部完成，调用方不用管尺寸。
     * @return 不可用 / 输入非法 / 推理异常时返回 null（一帧失败不该打断取景）
     */
    fun score(rgba: Mat): Result? {
        if (!ready) return null
        val net = interpreter ?: return null
        if (rgba.empty() || rgba.type() != CvType.CV_8UC4) return null

        return try {
            centerCropSquare(rgba, SIDE)
            cropped.get(0, 0, rgbaBytes)

            // RGBA → RGB，归一化到 [-1,1]
            inputFloats.clear()
            var i = 0
            while (i < rgbaBytes.size) {
                inputFloats.put(rgbaBytes[i].toInt().and(0xFF) / 127.5f - 1f)
                inputFloats.put(rgbaBytes[i + 1].toInt().and(0xFF) / 127.5f - 1f)
                inputFloats.put(rgbaBytes[i + 2].toInt().and(0xFF) / 127.5f - 1f)
                i += 4
            }
            inputFloats.rewind()
            inputBuffer.rewind()

            net.run(inputBuffer, output)

            var expected = 0f
            for (b in 0 until BINS) expected += (b + 1) * output[0][b]
            if (expected.isNaN() || expected <= 0f) return null

            Result(
                score = expected.coerceIn(1f, 10f),
                distribution = output[0].copyOf(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** 短边缩到 [side] 再中心裁剪成 [side]x[side] 正方形，结果写入 [cropped] */
    private fun centerCropSquare(src: Mat, side: Int) {
        val w = src.cols()
        val h = src.rows()
        if (w <= 0 || h <= 0) return
        val ratio = side.toFloat() / minOf(w, h)
        val nw = (w * ratio).roundToInt().coerceAtLeast(side)
        val nh = (h * ratio).roundToInt().coerceAtLeast(side)
        Imgproc.resize(src, scaled, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val x0 = (nw - side) / 2
        val y0 = (nh - side) / 2
        scaled.submat(y0, y0 + side, x0, x0 + side).copyTo(cropped)
    }

    override fun close() {
        runCatching { interpreter?.close() }
        runCatching { scaled.release() }
        runCatching { cropped.release() }
    }

    companion object {
        /** 模型输入边长 */
        const val SIDE = 224

        /** 分布桶数（1..10 分） */
        const val BINS = 10

        const val FLOAT_BYTES = 4
        const val THREADS = 2

        /**
         * 从 assets 读模型到**直接字节缓冲**。
         *
         * 不用 `AssetFileDescriptor` + `map`：APK 里模型可能被 zip 压缩，此时
         * `openFd()` 会抛 `FileNotFoundException`。老老实实读完再放进 direct buffer，
         * 一次性 6MB 拷贝，构造期开销，可以接受。
         */
        private fun loadAsset(context: Context, name: String): ByteBuffer? =
            runCatching {
                context.assets.open(name).use { input ->
                    val bytes = input.readBytes()
                    ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                        put(bytes)
                        rewind()
                    }
                }
            }.getOrNull()
    }
}
