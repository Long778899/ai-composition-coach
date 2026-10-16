package com.lemon.shotcoach.camera

import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Rational
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import com.lemon.shotcoach.filter.ColorTemp
import kotlin.math.roundToInt

/**
 * 把 [CameraParams] 真正下发到相机硬件。
 *
 * == 通道分工 ==
 *  - **CameraX `CameraControl`**：曝光补偿、变焦、手电筒、对焦触发 —— 接口稳定、能拿到范围。
 *  - **`Camera2CameraControl`**：ISO、快门、色温增益、手动对焦距离、AWB/AE/AF 模式 ——
 *    这些 CameraX 没有公开 API，只能通过 Camera2 互操作层下发。
 *
 * == 一个必须知道的坑 ==
 * 只要 ISO 或快门**任一**为手动，就必须把 `CONTROL_AE_MODE` 设为 OFF，
 * 否则设置 ISO/曝光时间会被 AE 直接覆盖、完全不生效。但 AE 关掉后，另一个
 * "自动"的项也失去依据 —— 于是用 [CameraParams.suggestExposure] 按当前亮度
 * 补一个合理值。这是所有手动相机 App 的共同取舍。
 *
 * == 作用面 ==
 * `captureRequestOptions` 会**持续**作用于后续所有帧（不是一次性），所以参数
 * 改变时重新调用 [apply] 即可，无需每帧下发。
 */
class CameraParamController {

    /** 下发结果 —— 告诉 UI 哪些值被硬件钳制了，避免滑杆与画面不一致 */
    data class Applied(
        val evIndex: Int,
        val zoomRatio: Float,
        val evClamped: Boolean,
        val zoomClamped: Boolean,
        val manualExposureSupported: Boolean,
        val manualFocusSupported: Boolean,
        val customWbSupported: Boolean,
    )

    /**
     * @param ambientBrightness 当前环境平均亮度（0..255），用于在部分手动时补齐另一项。
     *                          传 null 表示没有测光数据（会用 100 作为中性值）。
     */
    fun apply(
        camera: Camera,
        params: CameraParams,
        ambientBrightness: Float? = null,
    ): Applied {
        val control = camera.cameraControl
        val zoomState = camera.cameraInfo.zoomState.value
        val evState = camera.cameraInfo.exposureState

        // ── 1. 曝光补偿 ────────────────────────────────────────
        val evRange: Range<Int>? = evState.exposureCompensationRange
        val step: Rational? = evState.exposureCompensationStep
        val evStepValue = step?.let { it.numerator.toFloat() / it.denominator.toFloat() } ?: 1f

        val desiredIndex = (params.ev / evStepValue).roundToInt()
        val hwMin = evRange?.lower ?: 0
        val hwMax = evRange?.upper ?: 0
        val clampedIndex = desiredIndex.coerceIn(hwMin, hwMax)
        val evClamped = clampedIndex != desiredIndex

        runCatching { control.setExposureCompensationIndex(clampedIndex) }

        // ── 2. 变焦 ────────────────────────────────────────────
        val zoomMin = zoomState?.minZoomRatio ?: 1f
        val zoomMax = zoomState?.maxZoomRatio ?: 1f
        val targetZoom = params.zoomRatio.coerceIn(zoomMin, zoomMax)
        val zoomClamped = kotlin.math.abs(targetZoom - params.zoomRatio) > 0.01f
        runCatching {
            control.setZoomRatio(targetZoom)
            // 线性变焦更符合滑杆直觉（默认是中心加权）
            if (zoomMax > zoomMin) control.setLinearZoom(
                ((targetZoom - zoomMin) / (zoomMax - zoomMin)).coerceIn(0f, 1f),
            )
        }

        // ── 3. 手电筒 / 对焦 ───────────────────────────────────
        runCatching { control.enableTorch(params.flash == FlashMode.TORCH) }

        if (params.focusMode == FocusMode.SINGLE) {
            // 单次对焦：对着画面中心触发一次。
            // 用 1×1 的 SurfaceOrientedMeteringPointFactory，(0.5,0.5) 即归一化画面中心 ——
            // 这样本类无需知道预览 View 的实际像素尺寸。
            runCatching {
                val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                val point = factory.createPoint(0.5f, 0.5f)
                control.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
            }
        }

        // ── 4. Camera2 互操作层 ────────────────────────────────
        val c2 = Camera2CameraControl.from(control)
        val builder = CaptureRequestOptions.Builder()

        var manualExposureSupported = false
        var manualFocusSupported = false
        var customWbSupported = false

        // 4a. ISO / 快门
        if (!params.isExposureFullyAuto) {
            val (suggestedIso, suggestedShutter) =
                CameraParams.suggestExposure(ambientBrightness ?: 100f)
            val iso = if (params.isIsoAuto) suggestedIso else params.iso
            val shutter = if (params.isShutterAuto) suggestedShutter else params.shutterNs

            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                iso.coerceIn(IsoRange.MIN, IsoRange.MAX),
            )
            builder.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                shutter.coerceIn(ShutterRange.MIN_NS, ShutterRange.MAX_NS),
            )
            manualExposureSupported = true
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON,
            )
        }

        // 4b. 白平衡
        when (params.wbPreset) {
            WhiteBalancePreset.AUTO -> builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
            )

            WhiteBalancePreset.CUSTOM -> {
                // 连续色温：关 AWB，下发手动增益
                val (rGain, gGain, bGain) = ColorTemp.gainsFor(params.wbTemperatureK)
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_OFF,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    android.hardware.camera2.params.RggbChannelVector(rGain, gGain, gGain, bGain),
                )
                customWbSupported = true
            }

            else -> builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                params.wbPreset.toCaptureRequestAwbMode(),
            )
        }

        // 4c. 手动对焦
        if (params.focusMode == FocusMode.MANUAL) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF,
            )
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                params.manualFocusDiopters.coerceIn(0f, 10f),
            )
            manualFocusSupported = true
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                if (params.focusMode == FocusMode.SINGLE) {
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                } else {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                },
            )
        }

        // 持续生效（不是一次性）
        runCatching { c2.captureRequestOptions = builder.build() }

        return Applied(
            evIndex = clampedIndex,
            zoomRatio = targetZoom,
            evClamped = evClamped,
            zoomClamped = zoomClamped,
            manualExposureSupported = manualExposureSupported,
            manualFocusSupported = manualFocusSupported,
            customWbSupported = customWbSupported,
        )
    }

    /**
     * 重置为全自动。切前后摄 / 退出专业模式时调用，
     * 否则上一条 `captureRequestOptions` 会残留（AE 仍是 OFF → 画面全黑）。
     */
    fun resetToAuto(camera: Camera) {
        val c2 = Camera2CameraControl.from(camera.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON,
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO,
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            .build()
        runCatching { c2.captureRequestOptions = options }
        runCatching { camera.cameraControl.enableTorch(false) }
        runCatching { camera.cameraControl.setZoomRatio(1f) }
        runCatching { camera.cameraControl.setExposureCompensationIndex(0) }
    }

    private fun WhiteBalancePreset.toCaptureRequestAwbMode(): Int = when (this) {
        WhiteBalancePreset.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        WhiteBalancePreset.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        WhiteBalancePreset.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WhiteBalancePreset.SHADE -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        WhiteBalancePreset.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        WhiteBalancePreset.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        WhiteBalancePreset.CUSTOM -> CaptureRequest.CONTROL_AWB_MODE_OFF
    }
}

/** 供 UI 显示：当前设备支持的 EV / 变焦范围文本 */
fun Camera.describeRanges(): String {
    val ev = cameraInfo.exposureState
    val step = ev.exposureCompensationStep?.let { it.numerator.toFloat() / it.denominator }
    val zoom = cameraInfo.zoomState.value
    val evText = ev.exposureCompensationRange?.let { r ->
        if (step != null) {
            val lo = r.lower * step
            val hi = r.upper * step
            String.format(java.util.Locale.US, "EV %.1f..%.1f (步长 %.2f)", lo, hi, step)
        } else "EV 不支持"
    } ?: "EV 不支持"
    val zoomText = zoom?.let { String.format(java.util.Locale.US, "zoom %.1fx..%.1fx", it.minZoomRatio, it.maxZoomRatio) }
        ?: "zoom 未知"
    return "$evText · $zoomText"
}
