package com.lemon.shotcoach.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/**
 * 相册查询。
 *
 * == 为什么不申请读取相册权限 ==
 * 本应用只读**自己写入的** MediaStore 条目。Android 的 MediaStore 在没有
 * READ_MEDIA_IMAGES 权限时，依然会把"本应用自己创建的"文件返回给你。
 * 所以相册页能正常看到 ShotCoach 拍的照片，而**不需要用户授权任何东西** ——
 * 这与"只申请相机一项权限"的隐私承诺是一致的。
 * （代价：看不到其他 App 拍的照片。对本应用来说这是特性，不是缺陷。）
 */
object MediaQuery {

    /** 我们写入的相册子目录（与 [MediaStoreSaver] 保持一致） */
    const val ALBUM_DIR = "ShotCoach"

    data class PhotoItem(
        val uri: Uri,
        val displayName: String,
        val dateAddedSec: Long,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
    )

    /**
     * 按时间倒序返回本应用拍摄的照片。
     * 任何查询异常都吞掉并返回空列表 —— 相册挂了不该拖垮取景页。
     */
    fun recent(context: Context, limit: Int = 120): List<PhotoItem> = runCatching {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
        )

        // 只挑我们自己的目录；API 29 以下没有 RELATIVE_PATH，退化为全量按时间排序
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            null
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("Pictures/$ALBUM_DIR%")
        } else {
            null
        }

        val out = ArrayList<PhotoItem>(minOf(limit, 32))
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(idCol)
                out += PhotoItem(
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getString(nameCol) ?: "photo",
                    dateAddedSec = c.getLong(dateCol),
                    width = c.getInt(wCol),
                    height = c.getInt(hCol),
                    sizeBytes = c.getLong(sCol),
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    /**
     * 取原图（用于全屏查看）。
     *
     * 带 [maxSize] 上限做二次采样 —— 一张 1200 万像素的照片直接解码成
     * ARGB_8888 要 48MB，在相册里连开几张就会 OOM。
     */
    fun fullImage(context: Context, uri: Uri, maxSize: Int = 1600): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, bounds)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2

        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, opts)
        }
    }.getOrNull()

    /** 取缩略图。失败返回 null（UI 显示占位方块）。 */
    fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                ContentUris.parseId(uri),
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
    }.getOrNull()

    /** 删除一张（相册页长按删除用） */
    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)
}
