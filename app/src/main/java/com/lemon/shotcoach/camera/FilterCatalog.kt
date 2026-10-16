package com.lemon.shotcoach.camera

/**
 * 滤镜目录。
 *
 * == 关键设计：零素材文件 ==
 * 传统做法是内置一堆 3D LUT 贴图（.png）。本项目**不带任何 LUT 素材**，
 * 而是把每个滤镜描述成一组**色彩变换参数**（[FilterSpec]），运行时由
 * [com.lemon.shotcoach.filter.LutBuilder] 程序化烘焙成 LUT。
 *
 * 这样做的好处：
 *  1. 包体不增长（一个 LUT 贴图约 1MB，20 个就是 20MB）；
 *  2. 可任意插值（拖动"强度"滑杆 = 在原图 LUT 与滤镜 LUT 之间插值）；
 *  3. 参数可导出为 .cube 交付给调色软件；
 *  4. 滤镜是**算法**，不是**美术资产** —— 不涉及任何素材版权。
 *
 * 滤镜名为自拟（不使用任何厂商商标）。
 */
data class FilterSpec(
    val id: String,
    val name: String,
    val group: FilterGroup,

    /** 色温 -1(冷)..+1(暖) */
    val temperature: Float = 0f,
    /** 色调 -1(绿)..+1(品红) */
    val tint: Float = 0f,
    /** 饱和度倍率 0..2，1 = 不变 */
    val saturation: Float = 1f,
    /** 对比度倍率 0.5..2 */
    val contrast: Float = 1f,
    /** 亮度偏移 -0.5..+0.5 */
    val brightness: Float = 0f,

    /** 暗部染色（分离色调）：阴影区往哪个色调靠，-1..1 双轴 */
    val shadowTint: Float = 0f,
    /** 高光染色 */
    val highlightTint: Float = 0f,
    /** 分离色调强度 0..1 */
    val splitStrength: Float = 0f,

    /** 褪色（提黑位）0..1 —— 胶片感的灵魂 */
    val fade: Float = 0f,
    /** 自然饱和度 0..1（保护已饱和区域） */
    val vibrance: Float = 0f,
    /** 黑白 */
    val monochrome: Boolean = false,
    /** 单色分离强度：黑白后往暖/冷偏 -1..1 */
    val monoTone: Float = 0f,

    /** 预览缩略图用的代表色（避免依赖素材图） */
    val swatch: Int = 0xFF888888.toInt(),
) {
    val isIdentity: Boolean get() = id == FilterCatalog.NONE_ID
}

enum class FilterGroup(val label: String) {
    BASIC("基础"),
    FILM("胶片"),
    CINEMA("电影"),
    MONO("黑白"),
    FRESH("清新"),
}

object FilterCatalog {

    const val NONE_ID = "none"

    val NONE = FilterSpec(
        id = NONE_ID, name = "原图", group = FilterGroup.BASIC, swatch = 0xFF9E9E9E.toInt(),
    )

    val ALL: List<FilterSpec> = listOf(
        NONE,

        // ── 基础 ─────────────────────────────────────────────
        FilterSpec(
            id = "warm_sun", name = "暖阳", group = FilterGroup.BASIC,
            temperature = 0.30f, saturation = 1.08f, contrast = 1.04f,
            highlightTint = 0.10f, splitStrength = 0.18f,
            swatch = 0xFFE8A33D.toInt(),
        ),
        FilterSpec(
            id = "cool_mist", name = "冷雾", group = FilterGroup.BASIC,
            temperature = -0.28f, saturation = 0.94f, contrast = 1.06f,
            shadowTint = -0.12f, splitStrength = 0.20f, fade = 0.06f,
            swatch = 0xFF6E8FB8.toInt(),
        ),
        FilterSpec(
            id = "high_key", name = "高调", group = FilterGroup.BASIC,
            brightness = 0.10f, contrast = 0.95f, saturation = 1.02f,
            vibrance = 0.15f, swatch = 0xFFF2EDE4.toInt(),
        ),
        FilterSpec(
            id = "low_key", name = "低调", group = FilterGroup.BASIC,
            brightness = -0.08f, contrast = 1.18f, saturation = 0.96f,
            swatch = 0xFF4A4A52.toInt(),
        ),

        // ── 胶片 ─────────────────────────────────────────────
        FilterSpec(
            id = "film_faded", name = "褪色胶片", group = FilterGroup.FILM,
            contrast = 0.88f, saturation = 0.86f, fade = 0.22f,
            shadowTint = -0.10f, highlightTint = 0.12f, splitStrength = 0.30f,
            swatch = 0xFFC9B79C.toInt(),
        ),
        FilterSpec(
            id = "film_green", name = "青绿胶片", group = FilterGroup.FILM,
            temperature = -0.10f, tint = -0.14f, contrast = 1.05f, saturation = 0.95f,
            shadowTint = -0.30f, highlightTint = 0.05f, splitStrength = 0.36f, fade = 0.10f,
            swatch = 0xFF7E9E88.toInt(),
        ),
        FilterSpec(
            id = "film_amber", name = "琥珀胶片", group = FilterGroup.FILM,
            temperature = 0.34f, tint = 0.06f, contrast = 1.02f, saturation = 1.10f,
            shadowTint = -0.06f, highlightTint = 0.32f, splitStrength = 0.34f, fade = 0.12f,
            swatch = 0xFFD08A4E.toInt(),
        ),
        FilterSpec(
            id = "film_rose", name = "玫瑰胶片", group = FilterGroup.FILM,
            temperature = 0.14f, tint = 0.16f, saturation = 1.05f, contrast = 0.98f,
            highlightTint = 0.24f, splitStrength = 0.30f, fade = 0.14f,
            swatch = 0xFFD98C96.toInt(),
        ),

        // ── 电影 ─────────────────────────────────────────────
        FilterSpec(
            id = "cine_teal_orange", name = "青橙", group = FilterGroup.CINEMA,
            temperature = 0.10f, contrast = 1.14f, saturation = 1.04f,
            shadowTint = -0.42f, highlightTint = 0.40f, splitStrength = 0.52f,
            swatch = 0xFF3E7C8C.toInt(),
        ),
        FilterSpec(
            id = "cine_noir", name = "暗夜", group = FilterGroup.CINEMA,
            temperature = -0.12f, contrast = 1.22f, saturation = 0.72f, brightness = -0.05f,
            shadowTint = -0.26f, splitStrength = 0.34f,
            swatch = 0xFF2E3A4A.toInt(),
        ),
        FilterSpec(
            id = "cine_desat", name = "冷灰", group = FilterGroup.CINEMA,
            temperature = -0.06f, saturation = 0.62f, contrast = 1.10f, fade = 0.14f,
            shadowTint = -0.14f, splitStrength = 0.24f,
            swatch = 0xFF8A9099.toInt(),
        ),

        // ── 黑白 ─────────────────────────────────────────────
        FilterSpec(
            id = "mono_classic", name = "经典黑白", group = FilterGroup.MONO,
            monochrome = true, contrast = 1.10f, swatch = 0xFFBDBDBD.toInt(),
        ),
        FilterSpec(
            id = "mono_contrast", name = "硬调黑白", group = FilterGroup.MONO,
            monochrome = true, contrast = 1.34f, swatch = 0xFF7A7A7A.toInt(),
        ),
        FilterSpec(
            id = "mono_warm", name = "暖棕黑白", group = FilterGroup.MONO,
            monochrome = true, monoTone = 0.5f, contrast = 1.06f, fade = 0.08f,
            swatch = 0xFFA98B6B.toInt(),
        ),
        FilterSpec(
            id = "mono_cool", name = "冷调黑白", group = FilterGroup.MONO,
            monochrome = true, monoTone = -0.5f, contrast = 1.12f,
            swatch = 0xFF8B98A9.toInt(),
        ),

        // ── 清新 ─────────────────────────────────────────────
        FilterSpec(
            id = "fresh_pastel", name = "柔光", group = FilterGroup.FRESH,
            brightness = 0.06f, contrast = 0.92f, saturation = 1.06f, vibrance = 0.28f,
            fade = 0.10f, swatch = 0xFFF4C9D2.toInt(),
        ),
        FilterSpec(
            id = "fresh_sky", name = "晴空", group = FilterGroup.FRESH,
            temperature = -0.14f, saturation = 1.12f, vibrance = 0.30f, contrast = 1.05f,
            highlightTint = -0.14f, splitStrength = 0.22f,
            swatch = 0xFF7EC8E3.toInt(),
        ),
        FilterSpec(
            id = "fresh_candy", name = "果糖", group = FilterGroup.FRESH,
            temperature = 0.08f, tint = 0.10f, saturation = 1.24f, vibrance = 0.35f,
            contrast = 1.02f, swatch = 0xFFFF9BB0.toInt(),
        ),
    )

    private val byId: Map<String, FilterSpec> = ALL.associateBy { it.id }

    fun byId(id: String): FilterSpec = byId[id] ?: NONE

    fun byGroup(group: FilterGroup): List<FilterSpec> = ALL.filter { it.group == group }

    /** 校验 ID 合法性（导入预设时用） */
    fun isValid(id: String): Boolean = byId.containsKey(id)
}
