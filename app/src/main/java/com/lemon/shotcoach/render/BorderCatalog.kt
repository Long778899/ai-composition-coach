package com.lemon.shotcoach.render

/**
 * 边框目录 —— **全部由代码绘制**，不含任何边框素材图片。
 *
 * 每个边框描述成一组几何与样式参数，由 [BorderRenderer] 在导出时用
 * Canvas 画出来。这样边框可以随输出分辨率自适应（不会因为素材是
 * 1080p 而在 4K 输出上模糊）。
 */
data class BorderSpec(
    val id: String,
    val name: String,
    val kind: BorderKind,

    /** 边框宽度占短边的比例 */
    val widthRatio: Float = 0.030f,

    /** 主色（ARGB） */
    val color: Int = 0xFFFFFFFF.toInt(),
    /** 次色（双线框/渐变框用） */
    val color2: Int = 0xFF000000.toInt(),

    /** 圆角半径占短边比例（>0 时生效） */
    val cornerRatio: Float = 0f,

    /** 内边距占短边比例（留白框用） */
    val insetRatio: Float = 0f,

    /** 是否在底部留出"宝丽来"宽边 */
    val polaroid: Boolean = false,

    /** 是否绘制胶片齿孔 */
    val sprocket: Boolean = false,

    /** 底部胶片边上的机型/时间字条 */
    val filmStripLabel: Boolean = false,
) {
    val isNone: Boolean get() = kind == BorderKind.NONE
}

enum class BorderKind {
    NONE,
    SOLID,        // 纯色宽边
    DOUBLE_LINE,  // 双细线
    ROUNDED,      // 圆角遮罩
    POLAROID,     // 宝丽来
    FILM,         // 胶片齿孔
    VIGNETTE_FRAME, // 内阴影框
}

object BorderCatalog {

    const val NONE_ID = "none"

    val NONE = BorderSpec(id = NONE_ID, name = "无边框", kind = BorderKind.NONE)

    val ALL: List<BorderSpec> = listOf(
        NONE,
        BorderSpec(
            id = "white_classic", name = "白框", kind = BorderKind.SOLID,
            widthRatio = 0.040f, color = 0xFFFFFFFF.toInt(),
        ),
        BorderSpec(
            id = "black_classic", name = "黑框", kind = BorderKind.SOLID,
            widthRatio = 0.040f, color = 0xFF000000.toInt(),
        ),
        BorderSpec(
            id = "cream_inset", name = "米白留白", kind = BorderKind.SOLID,
            widthRatio = 0.060f, color = 0xFFF6F1E7.toInt(), insetRatio = 0.020f,
        ),
        BorderSpec(
            id = "double_thin", name = "双细线", kind = BorderKind.DOUBLE_LINE,
            widthRatio = 0.026f, color = 0xFFFFFFFF.toInt(), color2 = 0xFF333333.toInt(),
        ),
        BorderSpec(
            id = "rounded_soft", name = "圆角", kind = BorderKind.ROUNDED,
            widthRatio = 0.018f, color = 0xFFFFFFFF.toInt(), cornerRatio = 0.045f,
        ),
        BorderSpec(
            id = "polaroid_white", name = "宝丽来", kind = BorderKind.POLAROID,
            widthRatio = 0.045f, color = 0xFFFAFAF7.toInt(), polaroid = true,
        ),
        BorderSpec(
            id = "film_35mm", name = "35mm 胶片", kind = BorderKind.FILM,
            widthRatio = 0.055f, color = 0xFF141414.toInt(), sprocket = true,
            filmStripLabel = true,
        ),
        BorderSpec(
            id = "film_kodak_style", name = "胶卷边", kind = BorderKind.FILM,
            widthRatio = 0.048f, color = 0xFF1E1E1E.toInt(), sprocket = true,
        ),
        BorderSpec(
            id = "inner_shadow", name = "内阴影", kind = BorderKind.VIGNETTE_FRAME,
            widthRatio = 0.050f,
        ),
        BorderSpec(
            id = "gold_line", name = "金线", kind = BorderKind.DOUBLE_LINE,
            widthRatio = 0.022f, color = 0xFFD4AF37.toInt(), color2 = 0xFF8A6D1F.toInt(),
        ),
    )

    private val byId: Map<String, BorderSpec> = ALL.associateBy { it.id }

    fun byId(id: String): BorderSpec = byId[id] ?: NONE

    fun isValid(id: String): Boolean = byId.containsKey(id)
}
