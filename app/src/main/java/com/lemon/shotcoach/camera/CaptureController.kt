package com.lemon.shotcoach.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaActionSound
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.lemon.shotcoach.data.MediaStoreSaver
import com.lemon.shotcoach.render.PhotoProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 拍照控制器：定时 → 快门 → 处理流水线 → 写入相册。
 *
 * == 为什么"处理"必须离开主线程 ==
 * 12MP 的三线性 LUT + 边框绘制约 1.5~3s。在主线程做会 ANR，
 * 而且预览会冻结。所以拆成：
 *  - 主线程：倒计时、快门音、UI 状态
 *  - [Dispatchers.Default]：裁切 → 滤镜 → 水印 → 边框 → 编码
 *
 * == 并发保护 ==
 * [busy] 标志保证同一时刻只有一次拍摄在跑。用户狂点快门时后续请求被拒绝
 * 而不是排队堆积（堆积会让内存爆掉 —— 每次拍摄要额外持有一张全尺寸 Bitmap）。
 */
class CaptureController(private val context: Context) {

    /** 拍摄过程事件 */
    sealed interface Event {
        /** 倒计时剩余秒数（每秒一次） */
        data class Countdown(val secondsLeft: Int) : Event
        /** 快门已触发 */
        data object Shutter : Event
        /** 处理中（UI 显示"处理中…"） */
        data object Processing : Event
        /** 保存成功 */
        data class Saved(val uri: Uri, val width: Int, val height: Int) : Event
        /** 失败 */
        data class Failed(val message: String) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shutterSound = MediaActionSound().apply {
        runCatching { load(MediaActionSound.SHUTTER_CLICK) }
    }

    private var imageCapture: ImageCapture? = null
    private var busy: Boolean = false

    val isBusy: Boolean get() = busy

    /** 绑定 CameraX 的 ImageCapture 实例；传入 null 表示解绑（页面退出） */
    fun bind(capture: ImageCapture?) {
        imageCapture = capture
    }

    /** 闪光灯模式是 ImageCapture 的属性（不是 CameraControl） */
    fun applyFlash(mode: FlashMode) {
        imageCapture?.flashMode = when (mode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            // 常亮由 CameraParamController 的 enableTorch 负责，拍照时按"开"处理
            FlashMode.TORCH -> ImageCapture.FLASH_MODE_ON
        }
    }

    /** 屏幕旋转后需同步，否则照片方向会不对 */
    fun updateTargetRotation(rotationDegrees: Int) {
        imageCapture?.targetRotation = rotationDegrees
    }

    /**
     * 执行一次拍摄。返回 false 表示被拒（正忙）。
     *
     * @param onCountdown 每秒回调剩余秒数，UI 画大数字用
     */
    fun capture(
        params: CameraParams,
        deviceLabel: String,
        isFront: Boolean,
        onCountdown: (Int) -> Unit = {},
        onEvent: (Event) -> Unit,
    ): Boolean {
        val capture = imageCapture ?: run {
            onEvent(Event.Failed("相机尚未就绪"))
            return false
        }
        if (busy) {
            onEvent(Event.Failed("正在处理上一张，请稍候"))
            return false
        }
        busy = true

        scope.launch {
            try {
                // 1. 定时
                val seconds = params.timer.seconds
                if (seconds > 0) {
                    for (s in seconds downTo 1) {
                        onCountdown(s)
                        onEvent(Event.Countdown(s))
                        delay(1000)
                    }
                    onCountdown(0)
                }

                // 2. 快门
                if (params.shutterSound) {
                    runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
                }
                onEvent(Event.Shutter)

                val takenAt = System.currentTimeMillis()
                val proxy = takePictureSuspending(capture)
                onEvent(Event.Processing)

                // 3. 处理 + 保存（后台线程）
                val bitmap = withContext(Dispatchers.Default) {
                    val raw = try {
                        proxy.decodeJpegBitmap()
                    } finally {
                        proxy.close()
                    }

                    // 按画幅裁切（居中裁切，不拉伸）
                    val cropped = PhotoProcessor.cropToAspect(raw, params.aspect.ratio)

                    // 镜像 → 滤镜 → 水印 → 边框
                    val processed = PhotoProcessor.process(
                        src = cropped,
                        params = params,
                        takenAt = takenAt,
                        deviceLabel = deviceLabel,
                        isFront = isFront,
                    )

                    val uri = MediaStoreSaver.save(context, processed, params, takenAt)
                    val result = uri?.let { processed.width to processed.height }

                    if (processed !== cropped) processed.recycle()
                    if (cropped !== raw) cropped.recycle()

                    uri to result
                }

                val (uri, size) = bitmap
                if (uri != null && size != null) {
                    onEvent(Event.Saved(uri, size.first, size.second))
                } else {
                    onEvent(Event.Failed("写入相册失败（可检查存储空间）"))
                }
            } catch (t: Throwable) {
                onEvent(Event.Failed(t.message ?: t::class.java.simpleName))
            } finally {
                busy = false
            }
        }
        return true
    }

    /** 连拍：[count] 张，间隔 [intervalMs]。整体仍受 [busy] 保护，逐张串行 */
    fun burst(
        count: Int,
        intervalMs: Long,
        params: CameraParams,
        deviceLabel: String,
        isFront: Boolean,
        onEvent: (Event) -> Unit,
    ) {
        scope.launch {
            val quiet = params.copy(timer = TimerOption.OFF, shutterSound = false)
            repeat(count) { i ->
                val accepted = capture(quiet, deviceLabel, isFront, onEvent = onEvent)
                if (!accepted) return@repeat
                // 等待本次拍摄结束再拍下一张
                while (busy) delay(50)
                if (i < count - 1) delay(intervalMs)
            }
        }
    }

    private suspend fun takePictureSuspending(capture: ImageCapture): ImageProxy =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ContextCompatExecutor(),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (cont.isActive) cont.resume(image) else image.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) {
                            cont.resumeWith(
                                Result.failure(
                                    IllegalStateException(
                                        "拍照失败：${exception.message}（code=${exception.imageCaptureError}）",
                                    ),
                                ),
                            )
                        }
                    }
                },
            )
            cont.invokeOnCancellation { /* ImageCapture 无法取消，忽略 */ }
        }

    fun release() {
        imageCapture = null
        runCatching { shutterSound.release() }
    }
}

/**
 * CameraX 1.3 ImageCapture delivers a JPEG [ImageProxy]. 1.3 has no
 * ImageProxy.toBitmap(), so decode the first plane and apply the capture rotation.
 */
private fun ImageProxy.decodeJpegBitmap(): Bitmap {
    val buffer = planes[0].buffer
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalStateException("Failed to decode captured JPEG")
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return decoded
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (upright !== decoded) decoded.recycle()
    return upright
}

/** 极简单线程 Executor：ImageCapture 回调只需不阻塞，用不着线程池 */
private class ContextCompatExecutor : java.util.concurrent.Executor {
    override fun execute(command: Runnable) {
        Thread(command, "shotcoach-capture").apply { isDaemon = true }.start()
    }
}
