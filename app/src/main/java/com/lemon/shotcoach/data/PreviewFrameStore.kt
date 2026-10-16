package com.lemon.shotcoach.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 取景帧共享仓。
 *
 * == 解决什么问题 ==
 * 滤镜页 / 边框页 / 水印页都希望"预览就是当前正在拍的画面"，而不是一张色卡。
 * 但取景帧由 [com.lemon.shotcoach.camera.CameraController] 在分析线程上产生，
 * 且那个 Bitmap 是**复用的**（换尺寸时会回收）。跨页面直接传引用会踩到
 * "已回收的 Bitmap"崩溃。
 *
 * 所以这里持有一份**独立的副本**（约 360×270，400KB 量级），并提供 [version]
 * 让 Compose 能感知更新。写入方只负责 [submit]，读取方拿到的永远是稳定对象。
 *
 * == 线程约定 ==
 * [submit] 可从任意线程调用（Snapshot 状态写入是线程安全的）；
 * [current] 只在组合中读取，配合 [version] 一起使用。
 */
object PreviewFrameStore {

    private var store: Bitmap? = null
    private var canvas: Canvas? = null

    /** 每提交一帧 +1；在组合里读取它即可订阅更新 */
    var version by mutableIntStateOf(0)
        private set

    /** 提交一帧（内部会拷贝到私有 Bitmap，调用方可以立即回收自己的那份） */
    fun submit(src: Bitmap) {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return
        val target = synchronized(this) {
            val cur = store
            if (cur != null && cur.width == src.width && cur.height == src.height && !cur.isRecycled) {
                cur
            } else {
                cur?.recycle()
                Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
                    store = it
                    canvas = Canvas(it)
                }
            }
        }
        canvas?.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, target.width, target.height), null)
        version++
    }

    /** 当前帧；没有则返回 null（调用方应回落到程序化色卡） */
    fun current(): Bitmap? = store?.takeIf { !it.isRecycled }

    fun clear() {
        synchronized(this) {
            store?.recycle()
            store = null
            canvas = null
        }
    }
}
