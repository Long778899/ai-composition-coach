package com.lemon.shotcoach.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.lemon.shotcoach.camera.CameraParams
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 通过 MediaStore 保存成片。
 *
 * == 为什么不用 File API ==
 * Android 10+ 起应用不能随意写公共目录。走 MediaStore 是**唯一无需任何存储权限**的
 * 正确姿势（API 29+ 完全不需要 WRITE_EXTERNAL_STORAGE），因此本 App 的权限清单
 * 里只有 CAMERA 一项。
 *
 * == EXIF ==
 * 拍摄参数（ISO/快门/EV/机型/时间/参数摘要）会写进 EXIF，这样照片在其他软件里
 * 也能看到当时用了什么参数 —— 这是"参数调节"这个功能闭环的最后一步。
 */
object MediaStoreSaver {

    private const val ALBUM_NAME = "ShotCoach"

    /**
     * @param bitmap 已经过滤镜/水印/边框处理的成片
     * @return 写入的 MediaStore Uri；失败返回 null
     */
    fun save(
        context: Context,
        bitmap: Bitmap,
        params: CameraParams,
        takenAtMillis: Long = System.currentTimeMillis(),
    ): Uri? {
        val format = params.saveFormat.effective()
        val name = buildFileName(takenAtMillis, format.ext)
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.DATE_ADDED, takenAtMillis / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, takenAtMillis)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + ALBUM_NAME,
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            val quality = if (format == com.lemon.shotcoach.camera.SaveFormat.PNG) 100 else params.quality
            resolver.openOutputStream(uri)?.use { out ->
                val ok = bitmap.compress(format.compressFormat(), quality, out)
                if (!ok) throw java.io.IOException("Bitmap.compress 返回 false（格式=${format.label}）")
                if (format == com.lemon.shotcoach.camera.SaveFormat.JPEG) {
                    out.flush()
                    writeExif(resolver, uri, out, params, takenAtMillis)
                }
            } ?: throw java.io.IOException("openOutputStream 返回 null")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (t: Throwable) {
            // 失败必须清理占位记录，否则相册里会留一条打不开的空图
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun writeExif(
        resolver: android.content.ContentResolver,
        uri: Uri,
        out: OutputStream,
        params: CameraParams,
        takenAtMillis: Long,
    ) {
        val exif = runCatching { ExifInterface(out) }.getOrNull() ?: return
        exif.setAttribute(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(java.util.Date(takenAtMillis)),
        )
        exif.setAttribute(ExifInterface.TAG_MAKE, "ShotCoach")
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        if (!params.isIsoAuto) {
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, params.iso.toString())
        }
        if (!params.isShutterAuto) {
            // TAG_EXPOSURE_TIME 单位：秒，写成 "1/500"
            val sec = params.shutterNs / 1_000_000_000.0
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, if (sec < 1) "1/${Math.round(1 / sec)}" else sec.toString())
        }
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, params.summary())
        if (params.lensFacing == CameraParams.LENS_FACING_FRONT) {
            exif.setAttribute(ExifInterface.TAG_LENS_MODEL, "front")
        }
        runCatching { exif.saveAttributes() }
    }

    private fun buildFileName(takenAtMillis: Long, ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(java.util.Date(takenAtMillis))
        return "SC_$ts.$ext"
    }

    /** SaveFormat → Bitmap.CompressFormat */
    private fun com.lemon.shotcoach.camera.SaveFormat.compressFormat(): Bitmap.CompressFormat =
        when (this) {
            com.lemon.shotcoach.camera.SaveFormat.JPEG -> Bitmap.CompressFormat.JPEG
            com.lemon.shotcoach.camera.SaveFormat.PNG -> Bitmap.CompressFormat.PNG
            com.lemon.shotcoach.camera.SaveFormat.HEIC ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.HEIC
                } else {
                    Bitmap.CompressFormat.JPEG
                }
        }
}

/**
 * 低版本回落的实际格式。
 *
 * HEIC 编码需要 API 30+。低版本上如果只把编码换成 JPEG 却仍然写
 * `image/heic` 的 MIME 和 `.heic` 后缀，相册里就会出现**打不开的坏图**
 * （扩展名与实际字节不符）。所以格式回落必须连 MIME 与后缀一起回落。
 */
private fun com.lemon.shotcoach.camera.SaveFormat.effective(): com.lemon.shotcoach.camera.SaveFormat =
    if (this == com.lemon.shotcoach.camera.SaveFormat.HEIC && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        com.lemon.shotcoach.camera.SaveFormat.JPEG
    } else {
        this
    }

/** 参数摘要，写进 EXIF 的 ImageDescription，方便事后复盘 */
fun CameraParams.summary(): String = buildString {
    append("ShotCoach | ")
    append("EV=").append(String.format(Locale.US, "%+.1f", ev)).append(' ')
    append("ISO=").append(isoLabel).append(' ')
    append("S=").append(shutterLabel).append(' ')
    append("WB=").append(wbPreset.label)
    if (wbPreset == com.lemon.shotcoach.camera.WhiteBalancePreset.CUSTOM) {
        append('(').append(wbTemperatureK).append("K)")
    }
    append(' ').append("zoom=").append(zoomLabel)
    append(' ').append("filter=").append(com.lemon.shotcoach.camera.FilterCatalog.byId(filterId).name)
    if (borderId != com.lemon.shotcoach.render.BorderCatalog.NONE_ID) {
        append(' ').append("border=").append(com.lemon.shotcoach.render.BorderCatalog.byId(borderId).name)
    }
}
