package com.lemon.shotcoach.data

import android.content.Context
import com.lemon.shotcoach.camera.CameraParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置仓库：相机参数的唯一持久化入口。
 *
 * == 两类设置，分开存 ==
 *  - **拍摄参数**（[CameraParams]）：以 [CameraParamsCodec] 的键值对形式平铺存进
 *    SharedPreferences，便于按 key 单独更新，也便于导出为"预设"。
 *  - **应用偏好**（[Prefs]）：单个布尔/整数开关，直接存。
 *
 * 所有写入走 `apply()`（异步落盘，不阻塞 UI 线程）。
 * 对外只暴露不可变的 [params] StateFlow，UI 通过 [update] 提交变更 —— 单向数据流。
 */
class SettingsRepository(context: Context) {

    private val prefs: android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _params = MutableStateFlow(load())
    val params: StateFlow<CameraParams> = _params.asStateFlow()

    // ── 拍摄参数 ──────────────────────────────────────────────

    private fun load(): CameraParams {
        val map = buildMap<String, String> {
            for (key in ALL_PARAM_KEYS) {
                prefs.getString(key, null)?.let { put(key, it) }
            }
        }
        return CameraParamsCodec.decode(map)
    }

    /** 以函数式变换提交参数变更（UI 唯一入口） */
    fun update(transform: (CameraParams) -> CameraParams) {
        val next = transform(_params.value)
        if (next == _params.value) return
        _params.value = next
        prefs.edit().apply {
            CameraParamsCodec.encode(next).forEach { (k, v) -> putString(k, v) }
        }.apply()
    }

    /** 恢复出厂：只重置拍摄参数，不动应用偏好 */
    fun resetParams() = update { CameraParams() }

    // ── 预设（用户自定义参数组合）────────────────────────────

    fun savePreset(name: String) {
        val clean = sanitize(name)
        if (clean.isEmpty()) return
        prefs.edit().apply {
            CameraParamsCodec.encode(_params.value).forEach { (k, v) -> putString("$PRESET_PREFIX$clean.$k", v) }
            putLong("$PRESET_PREFIX$clean.$K_SAVED_AT", System.currentTimeMillis())
        }.apply()
    }

    fun listPresets(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(PRESET_PREFIX) && it.endsWith(".$K_SAVED_AT") }
            .map { it.removePrefix(PRESET_PREFIX).removeSuffix(".$K_SAVED_AT") }
            .sorted()

    /** @return 是否成功加载（预设不存在时返回 false 且不改变当前参数） */
    fun loadPreset(name: String): Boolean {
        val clean = sanitize(name)
        val map = buildMap<String, String> {
            for (key in ALL_PARAM_KEYS) {
                prefs.getString("$PRESET_PREFIX$clean.$key", null)?.let { put(key, it) }
            }
        }
        if (map.isEmpty()) return false
        val decoded = CameraParamsCodec.decode(map)
        _params.value = decoded
        prefs.edit().apply {
            CameraParamsCodec.encode(decoded).forEach { (k, v) -> putString(k, v) }
        }.apply()
        return true
    }

    fun deletePreset(name: String) {
        val clean = sanitize(name)
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("$PRESET_PREFIX$clean.") }.forEach { remove(it) }
        }.apply()
    }

    fun exportPresetAsCubeText(name: String): String? {
        val clean = sanitize(name)
        if (!prefs.contains("$PRESET_PREFIX$clean.$K_SAVED_AT")) return null
        val map = buildMap<String, String> {
            for (key in ALL_PARAM_KEYS) {
                prefs.getString("$PRESET_PREFIX$clean.$key", null)?.let { put(key, it) }
            }
        }
        val p = CameraParamsCodec.decode(map)
        return buildString {
            appendLine("# ShotCoach 参数预设：$clean")
            appendLine("# 导出时间：${java.util.Date()}")
            appendLine()
            CameraParamsCodec.encode(p).toSortedMap().forEach { (k, v) -> appendLine("$k = $v") }
        }
    }

    private fun sanitize(name: String) =
        name.trim().replace(Regex("[^\\p{L}\\p{N}_\\- ]"), "").take(32)

    // ── 应用偏好 ──────────────────────────────────────────────

    /** 拍照后额外存一份到系统相册（MediaStore） */
    var saveToAlbum: Boolean
        get() = prefs.getBoolean(Prefs.SAVE_TO_ALBUM, true)
        set(v) = prefs.edit().putBoolean(Prefs.SAVE_TO_ALBUM, v).apply()

    /** 音量键当快门 */
    var volumeKeyShutter: Boolean
        get() = prefs.getBoolean(Prefs.VOLUME_SHUTTER, true)
        set(v) = prefs.edit().putBoolean(Prefs.VOLUME_SHUTTER, v).apply()

    /** 拍摄期间保持屏幕常亮 */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(Prefs.KEEP_SCREEN_ON, true)
        set(v) = prefs.edit().putBoolean(Prefs.KEEP_SCREEN_ON, v).apply()

    /** 是否把网格线/构图叠加层画进成片（默认不画） */
    var burnOverlayIntoPhoto: Boolean
        get() = prefs.getBoolean(Prefs.BURN_OVERLAY, false)
        set(v) = prefs.edit().putBoolean(Prefs.BURN_OVERLAY, v).apply()

    /** 构图评分徽标是否显示在取景页 */
    var showScoreBadge: Boolean
        get() = prefs.getBoolean(Prefs.SHOW_SCORE, false)
        set(v) = prefs.edit().putBoolean(Prefs.SHOW_SCORE, v).apply()

    /** 人脸通道开关（关掉可省 ~2ms/帧） */
    var faceDetectEnabled: Boolean
        get() = prefs.getBoolean(Prefs.FACE_DETECT, true)
        set(v) = prefs.edit().putBoolean(Prefs.FACE_DETECT, v).apply()

    /** 分析抽帧间隔（1 = 每帧分析；越大越省电） */
    var analysisEveryNFrames: Int
        get() = prefs.getInt(Prefs.ANALYZE_EVERY_N, 3).coerceIn(1, 10)
        set(v) = prefs.edit().putInt(Prefs.ANALYZE_EVERY_N, v.coerceIn(1, 10)).apply()

    /** EMA 平滑系数（0.1..1.0），越小提示越稳 */
    var emaAlpha: Float
        get() = prefs.getFloat(Prefs.EMA_ALPHA, 0.35f).coerceIn(0.1f, 1f)
        set(v) = prefs.edit().putFloat(Prefs.EMA_ALPHA, v.coerceIn(0.1f, 1f)).apply()

    /** 提示最少驻留时间（毫秒），防抖 */
    var hintDwellMs: Long
        get() = prefs.getLong(Prefs.HINT_DWELL, 800L).coerceIn(200L, 3000L)
        set(v) = prefs.edit().putLong(Prefs.HINT_DWELL, v.coerceIn(200L, 3000L)).apply()

    // ── 首次启动标记（引导页用）─────────────────────────────

    fun isFirstRun(): Boolean = !prefs.getBoolean(Prefs.GUIDE_DONE, false)

    fun markGuideDone() = prefs.edit().putBoolean(Prefs.GUIDE_DONE, true).apply()

    companion object {
        private const val PREFS_NAME = "shotcoach_prefs"
        private const val PRESET_PREFIX = "preset_"
        private const val K_SAVED_AT = "savedAt"

        /**
         * 需要持久化的参数键全集（与 CameraParamsCodec 保持一致）。
         * 漏一个键 = 该参数重启后丢失，所以这里加键时务必同步 codec。
         */
        private val ALL_PARAM_KEYS = listOf(
            "ev", "iso", "shutter", "wb", "wbK", "focus", "focusD", "zoom", "flash",
            "grid", "aspect", "level", "guide", "timer", "mirror", "filter", "filterS", "border",
            "sound", "format", "quality", "lens",
            "wmOn", "wmTime", "wmDevice", "wmText", "wmPos", "wmOpacity", "wmColor", "wmSize",
        )

        object Prefs {
            const val SAVE_TO_ALBUM = "save_to_album"
            const val VOLUME_SHUTTER = "volume_shutter"
            const val KEEP_SCREEN_ON = "keep_screen_on"
            const val BURN_OVERLAY = "burn_overlay"
            const val SHOW_SCORE = "show_score"
            const val FACE_DETECT = "face_detect"
            const val ANALYZE_EVERY_N = "analyze_every_n"
            const val EMA_ALPHA = "ema_alpha"
            const val HINT_DWELL = "hint_dwell"
            const val GUIDE_DONE = "guide_done"
        }
    }
}
