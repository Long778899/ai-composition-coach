package com.lemon.shotcoach.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 文本类文件导出（.cube LUT、预设说明等）。
 *
 * 走 MediaStore.Downloads —— 写自己创建的下载文件**不需要任何权限**，
 * 与"只申请相机权限"的承诺一致。API 29 以下退化为应用外部私有目录。
 */
object TextFileSaver {

    const val DIR = "ShotCoach"

    /** @return 写入后的 Uri；失败返回 null */
    fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String,
    ): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            f.writeText(content, Charsets.UTF_8)
            Uri.fromFile(f)
        }
    }.getOrNull()
}
