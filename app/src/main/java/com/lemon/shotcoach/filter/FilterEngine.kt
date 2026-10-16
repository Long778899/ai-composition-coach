package com.lemon.shotcoach.filter

import android.graphics.Bitmap
import com.lemon.shotcoach.camera.FilterCatalog
import com.lemon.shotcoach.camera.FilterSpec

/**
 * 滤镜能力的统一门面。UI / 拍摄 / 预览三条路径都只跟这个类打交道。
 *
 * == 为什么要有个门面 ==
 * 同一个滤镜要在三个地方用，但要求完全不同：
 *  1. **实时预览**（每帧，几十毫秒预算）→ [applyFastToPixels]，5bit 快查表
 *  2. **导出成片**（一次，可等 1~2s）→ [applyTrilinearToBitmap]，三线性插值
 *  3. **UI 缩略色**（选滤镜时的小圆点）→ 直接用 `FilterSpec.swatch`，不渲染
 *
 * 把"烘焙 LUT"和"应用 LUT"分开，并缓存 32³ 快查表 —— 滑杆拖动时只做
 * 整数 lerp，绝不重新烘焙（烘焙一次 33³ 要 10~20ms，每帧烘焙必卡）。
 */
object FilterEngine {

    private const val MAX_CACHE = 8

    /** 快查表缓存：key = 滤镜ID@强度(1/16 量化) */
    private val fastCache = object : LinkedHashMap<String, IntArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>): Boolean =
            size > MAX_CACHE
    }

    private fun cacheKey(spec: FilterSpec, strength: Float): String =
        "${spec.id}@${(strength.coerceIn(0f, 1f) * 16f).toInt()}"

    /**
     * 取得（并缓存）快查表。强度量化到 1/16 步进，避免拖动滑杆时缓存无限增长。
     */
    @Synchronized
    fun fastTable(spec: FilterSpec, strength: Float): IntArray {
        val key = cacheKey(spec, strength)
        fastCache[key]?.let { return it }
        val table = LutBuilder.bake(spec, strength).buildFastTable()
        fastCache[key] = table
        return table
    }

    @Synchronized
    fun clearCache() {
        fastCache.clear()
    }

    // ── 像素级 ────────────────────────────────────────────────

    /**
     * 实时预览路径：快查表 + 强度整数混合。**就地修改** pixels。
     */
    fun applyFastToPixels(pixels: IntArray, spec: FilterSpec, strength: Float) {
        if (spec.isIdentity || strength <= 0f) return
        CpuLutApplier.applyFastBlended(pixels, fastTable(spec, strength), strength)
    }

    /**
     * 导出路径：三线性插值，质量优先。**就地修改** pixels。
     */
    fun applyTrilinearToPixels(pixels: IntArray, spec: FilterSpec, strength: Float) {
        if (spec.isIdentity || strength <= 0f) return
        CpuLutApplier.applyTrilinear(pixels, LutBuilder.bake(spec, strength))
    }

    // ── Bitmap 级 ─────────────────────────────────────────────

    /**
     * 导出用：返回**新 Bitmap**（不改动入参，便于保留原图）。
     */
    fun applyTrilinearToBitmap(src: Bitmap, spec: FilterSpec, strength: Float): Bitmap {
        if (spec.isIdentity || strength <= 0f) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        applyTrilinearToPixels(pixels, spec, strength)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 实时预览用：把 [src] 的像素套上滤镜后写入 [reuse]（尺寸一致时复用，避免每帧分配）。
     */
    fun renderPreview(src: Bitmap, spec: FilterSpec, strength: Float, reuse: Bitmap?): Bitmap {
        val w = src.width
        val h = src.height
        val target = if (reuse != null && reuse.width == w && reuse.height == h && !reuse.isRecycled) {
            reuse
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        applyFastToPixels(pixels, spec, strength)
        target.setPixels(pixels, 0, w, 0, 0, w, h)
        return target
    }

    // ── 工具 ─────────────────────────────────────────────────

    /**
     * 生成滤镜缩略图（滤镜选择条用）。
     *
     * [base] 为 null 时用程序化色卡 [SwatchPattern] —— 保证面板一打开就有内容，
     * 不用等相机出帧。相机出帧后 UI 会传入真实画面，缩略图随之变成实景。
     *
     * 走 [applyFastToPixels] 而非三线性：缩略图只有 ~84px，快查表足够，
     * 而且十几张缩略图要在一帧内生成完，必须够快。
     */
    fun previewSwatch(
        base: Bitmap?,
        spec: FilterSpec,
        strength: Float,
        size: Int,
        reuse: Bitmap? = null,
    ): Bitmap {
        val target = if (reuse != null && reuse.width == size && reuse.height == size && !reuse.isRecycled) {
            reuse
        } else {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }
        val pixels = if (base != null && base.width > 0 && base.height > 0) {
            centerCropPixels(base, size)
        } else {
            SwatchPattern.build(size)
        }
        applyFastToPixels(pixels, spec, strength)
        target.setPixels(pixels, 0, size, 0, 0, size, size)
        return target
    }

    /** 把 [src] 居中裁剪并缩放到 [size]×[size]，返回 ARGB 像素数组 */
    private fun centerCropPixels(src: Bitmap, size: Int): IntArray {
        val side = minOf(src.width, src.height)
        val x0 = (src.width - side) / 2
        val y0 = (src.height - side) / 2
        val srcPixels = IntArray(side * side)
        src.getPixels(srcPixels, 0, side, x0, y0, side, side)

        val out = IntArray(size * size)
        // 最近邻缩放：缩略图尺寸很小，质量差异不可见，但省掉一次 Bitmap 分配
        for (y in 0 until size) {
            val sy = (y * side) / size
            val rowBase = sy * side
            val outBase = y * size
            for (x in 0 until size) {
                out[outBase + x] = srcPixels[rowBase + (x * side) / size]
            }
        }
        return out
    }

    /** 取滤镜代表色（无素材图，用目录里声明的 swatch） */
    fun swatchOf(id: String): Int = FilterCatalog.byId(id).swatch

    /**
     * 生成"滤镜说明"文本，用于滤镜详情页与 .cube 导出提示。
     */
    fun describe(spec: FilterSpec, strength: Float): String {
        val parts = mutableListOf<String>()
        if (spec.monochrome) parts += "黑白"
        if (spec.temperature != 0f) parts += if (spec.temperature > 0) "偏暖 +${(spec.temperature * 100).toInt()}" else "偏冷 ${(spec.temperature * 100).toInt()}"
        if (spec.tint != 0f) parts += if (spec.tint > 0) "偏品红" else "偏绿"
        if (spec.saturation != 1f) parts += "饱和 ${(spec.saturation * 100).toInt()}%"
        if (spec.contrast != 1f) parts += "对比 ${(spec.contrast * 100).toInt()}%"
        if (spec.fade > 0f) parts += "褪色 ${(spec.fade * 100).toInt()}%"
        if (spec.splitStrength > 0f) parts += "分离色调 ${(spec.splitStrength * 100).toInt()}%"
        if (parts.isEmpty()) parts += "无色彩变换"
        return "${spec.name}（强度 ${(strength * 100).toInt()}%）：" + parts.joinToString("、")
    }
}
