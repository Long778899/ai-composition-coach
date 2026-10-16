package com.lemon.shotcoach

import com.lemon.shotcoach.camera.AspectRatio
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.camera.FilterCatalog
import com.lemon.shotcoach.camera.FlashMode
import com.lemon.shotcoach.camera.FocusMode
import com.lemon.shotcoach.camera.GridStyle
import com.lemon.shotcoach.camera.IsoRange
import com.lemon.shotcoach.camera.MirrorMode
import com.lemon.shotcoach.camera.SaveFormat
import com.lemon.shotcoach.camera.ShutterRange
import com.lemon.shotcoach.camera.TimerOption
import com.lemon.shotcoach.camera.WatermarkConfig
import com.lemon.shotcoach.camera.WatermarkPosition
import com.lemon.shotcoach.camera.WhiteBalancePreset
import com.lemon.shotcoach.data.CameraParamsCodec
import com.lemon.shotcoach.filter.ColorTemp
import com.lemon.shotcoach.filter.CpuLutApplier
import com.lemon.shotcoach.filter.Lut3D
import com.lemon.shotcoach.filter.LutBuilder
import com.lemon.shotcoach.filter.SwatchPattern
import com.lemon.shotcoach.render.BorderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 滤镜引擎 / 参数模型 的单元测试。
 *
 * == 这些测试能跑，是因为领域层不碰 Android ==
 * [Lut3D]、[LutBuilder]、[CpuLutApplier]、[SwatchPattern]、[ColorTemp]、
 * [CameraParams]、[CameraParamsCodec] 全部是纯 Kotlin，没有任何
 * `android.*` 依赖，所以直接在 JVM 上 `./gradlew test` 即可，不需要真机。
 *
 * 这是本项目刻意的分层约束：能算的东西不要绑在 Android 上。
 */
class LutAndParamsTest {

    // ── 工具 ─────────────────────────────────────────────────

    private fun sample(lut: Lut3D, r: Float, g: Float, b: Float): FloatArray =
        FloatArray(3).also { lut.sampleTrilinear(r, g, b, it) }

    private fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun channel(p: Int, shift: Int): Int = (p shr shift) and 0xFF

    // ── LUT 烘焙 ─────────────────────────────────────────────

    @Test
    fun `原图滤镜烘焙出来必须是恒等变换`() {
        val lut = LutBuilder.bake(FilterCatalog.NONE, strength = 1f)
        for (v in listOf(0f, 0.13f, 0.5f, 0.77f, 1f)) {
            val out = sample(lut, v, v, v)
            assertEquals(v, out[0], 1e-3f)
            assertEquals(v, out[1], 1e-3f)
            assertEquals(v, out[2], 1e-3f)
        }
    }

    @Test
    fun `强度为 0 时任何滤镜都应等价于原图`() {
        val spec = FilterCatalog.ALL.first { !it.isIdentity }
        val lut = LutBuilder.bake(spec, strength = 0f)
        val out = sample(lut, 0.8f, 0.4f, 0.2f)
        assertEquals(0.8f, out[0], 1e-3f)
        assertEquals(0.4f, out[1], 1e-3f)
        assertEquals(0.2f, out[2], 1e-3f)
    }

    @Test
    fun `黑白滤镜的三个通道必须相等`() {
        val mono = FilterCatalog.ALL.filter { it.monochrome && !it.isIdentity }
        assertTrue("目录里应至少有一个黑白滤镜", mono.isNotEmpty())
        val lut = LutBuilder.bake(mono.first(), strength = 1f)
        val out = sample(lut, 0.9f, 0.3f, 0.55f)
        assertEquals(out[0], out[1], 2e-3f)
        assertEquals(out[1], out[2], 2e-3f)
    }

    @Test
    fun `暖调滤镜应提高红通道、压低蓝通道`() {
        val warm = FilterCatalog.ALL.firstOrNull { it.temperature > 0.2f && !it.monochrome }
            ?: return
        val lut = LutBuilder.bake(warm, strength = 1f)
        val out = sample(lut, 0.5f, 0.5f, 0.5f)
        assertTrue("暖调下红通道应不低于中性：${out[0]}", out[0] >= 0.5f - 1e-3f)
        assertTrue("暖调下蓝通道应不高于中性：${out[2]}", out[2] <= 0.5f + 1e-3f)
    }

    @Test
    fun `烘焙结果的每一个采样点都必须落在 0 到 1 之间`() {
        for (spec in FilterCatalog.ALL) {
            val lut = LutBuilder.bake(spec, strength = 1f)
            val data = lut.data
            for (v in data) {
                assertTrue("${spec.id} 出现越界值 $v", v >= -1e-4f && v <= 1.0001f)
            }
        }
    }

    // ── 两条应用路径必须一致 ───────────────────────────────────

    @Test
    fun `快查表与三线性插值的结果应足够接近`() {
        val spec = FilterCatalog.ALL.first { !it.isIdentity }
        val lut = LutBuilder.bake(spec, strength = 1f)
        val table = lut.buildFastTable()

        val original = intArrayOf(
            pack(200, 100, 50),
            pack(10, 240, 130),
            pack(128, 128, 128),
            pack(255, 255, 255),
            pack(0, 0, 0),
        )
        val slow = original.copyOf()
        val fast = original.copyOf()
        CpuLutApplier.applyTrilinear(slow, lut)
        CpuLutApplier.applyFast(fast, table)

        for (i in original.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val diff = abs(channel(slow[i], shift) - channel(fast[i], shift))
                assertTrue("像素 $i 通道 $shift 差异过大：$diff", diff <= 8)
            }
        }
    }

    @Test
    fun `恒等快查表不应改变任何像素`() {
        val table = LutBuilder.bake(FilterCatalog.NONE, strength = 1f).buildFastTable()
        val px = intArrayOf(pack(12, 200, 77), pack(255, 0, 128))
        val before = px.copyOf()
        CpuLutApplier.applyFast(px, table)
        for (i in px.indices) {
            assertEquals(before[i], px[i])
        }
    }

    @Test
    fun `混合强度应介于原值与全强度之间`() {
        val spec = FilterCatalog.ALL.first { !it.isIdentity }
        val table = LutBuilder.bake(spec, strength = 1f).buildFastTable()

        val full = intArrayOf(pack(180, 90, 40))
        val half = full.copyOf()
        val none = full.copyOf()
        CpuLutApplier.applyFast(full, table)
        CpuLutApplier.applyFastBlended(half, table, strength = 0.5f)
        CpuLutApplier.applyFastBlended(none, table, strength = 0f)

        assertEquals(pack(180, 90, 40), none[0])
        // 半强度 → 红通道落在两者之间（含端点）
        val r0 = 180
        val rFull = channel(full[0], 16)
        val rHalf = channel(half[0], 16)
        assertTrue("半强度 $rHalf 应介于 $r0 与 $rFull", rHalf in minOf(r0, rFull)..maxOf(r0, rFull))
    }

    // ── 色卡 ─────────────────────────────────────────────────

    @Test
    fun `色卡尺寸正确且全部不透明`() {
        val w = 48
        val h = 32
        val px = SwatchPattern.build(w, h)
        assertEquals(w * h, px.size)
        for (p in px) {
            assertEquals("色卡必须完全不透明", 0xFF, (p ushr 24) and 0xFF)
        }
    }

    // ── 色温 ─────────────────────────────────────────────────

    @Test
    fun `低色温偏红 高色温偏蓝`() {
        val (r3000, _, b3000) = ColorTemp.kelvinToRgb(3000)
        val (r8000, _, b8000) = ColorTemp.kelvinToRgb(8000)
        assertTrue("3000K 应偏红：r=$r3000 b=$b3000", r3000 > b3000)
        assertTrue("8000K 应偏蓝：r=$r8000 b=$b8000", b8000 > r8000)
    }

    @Test
    fun `色温增益必须为正值`() {
        for (k in listOf(2000, 4000, 5500, 6500, 8000)) {
            val (r, g, b) = ColorTemp.gainsFor(k)
            assertTrue("k=$k r=$r", r > 0f)
            assertTrue("k=$k g=$g", g > 0f)
            assertTrue("k=$k b=$b", b > 0f)
        }
    }

    // ── 参数模型 ──────────────────────────────────────────────

    @Test
    fun `默认参数应处于全自动状态`() {
        val p = CameraParams()
        assertTrue(p.isIsoAuto)
        assertTrue(p.isShutterAuto)
        assertTrue(p.isExposureFullyAuto)
        assertEquals(0f, p.ev, 1e-6f)
        assertTrue("默认不应带滤镜", p.filterId == FilterCatalog.NONE_ID)
        assertTrue("默认不应带边框", p.borderId == BorderCatalog.NONE_ID)
        assertTrue("默认不应启用分析水印", !p.watermark.enabled)
    }

    @Test
    fun `一键建议的 ISO 与快门必须落在允许区间内`() {
        for (brightness in listOf(0f, 30f, 80f, 150f, 255f)) {
            val (iso, shutter) = CameraParams.suggestExposure(brightness)
            assertTrue("亮度 $brightness 给出的 ISO $iso 越界", iso in IsoRange.MIN..IsoRange.MAX)
            assertTrue(
                "亮度 $brightness 给出的快门 $shutter 越界",
                shutter in ShutterRange.MIN_NS..ShutterRange.MAX_NS,
            )
        }
    }

    @Test
    fun `快门的人类可读表示不应为空`() {
        for (ns in listOf(500_000L, 2_000_000L, 125_000_000L, 1_000_000_000L)) {
            assertTrue(CameraParams.formatShutter(ns).isNotBlank())
        }
        assertEquals("自动", CameraParams.formatShutter(0L))
    }

    // ── 参数编解码（持久化 + 预设的基石）────────────────────────

    @Test
    fun `非默认参数编解码往返必须完全一致`() {
        val p = CameraParams(
            evIndex = 3,
            iso = 800,
            shutterNs = 2_000_000L,
            wbPreset = WhiteBalancePreset.CUSTOM,
            wbTemperatureK = 4200,
            focusMode = FocusMode.MANUAL,
            manualFocusDiopters = 3.5f,
            zoomRatio = 2f,
            flash = FlashMode.TORCH,
            grid = GridStyle.DIAGONAL,
            aspect = AspectRatio.RATIO_1_1,
            levelEnabled = false,
            guideEnabled = false,
            timer = TimerOption.S10,
            mirrorFront = MirrorMode.ON,
            filterId = FilterCatalog.ALL[3].id,
            filterStrength = 0.6f,
            watermark = WatermarkConfig(
                enabled = true,
                showTime = true,
                showDevice = true,
                customText = "测试水印",
                position = WatermarkPosition.TOP_RIGHT,
                opacity = 0.5f,
                color = 0xFFFF0000.toInt(),
                fontSizeRatio = 0.05f,
            ),
            borderId = BorderCatalog.ALL[2].id,
            shutterSound = false,
            saveFormat = SaveFormat.PNG,
            quality = 88,
            lensFacing = CameraParams.LENS_FACING_FRONT,
        )
        assertNull(CameraParamsCodec.roundTripDiff(p))
    }

    @Test
    fun `空字典解码得到默认参数`() {
        assertEquals(CameraParams(), CameraParamsCodec.decode(emptyMap()))
    }

    @Test
    fun `非法取值应回落到默认值而不是抛异常`() {
        val decoded = CameraParamsCodec.decode(
            mapOf(
                "ev" to "不是数字",
                "iso" to "??",
                "zoom" to "",
                "level" to "maybe",
            ),
        )
        assertEquals(0, decoded.evIndex)
        assertEquals(CameraParams().iso, decoded.iso)
        assertEquals(CameraParams().zoomRatio, decoded.zoomRatio, 1e-6f)
        assertEquals(CameraParams().levelEnabled, decoded.levelEnabled)
    }

    @Test
    fun `存档里已不存在的滤镜与边框必须回落到无`() {
        val decoded = CameraParamsCodec.decode(
            mapOf(
                "filter" to "已删除的滤镜ID",
                "border" to "已删除的边框ID",
            ),
        )
        assertEquals(FilterCatalog.NONE_ID, decoded.filterId)
        assertEquals(BorderCatalog.NONE_ID, decoded.borderId)
    }

    @Test
    fun `每个内置滤镜与边框的 ID 都必须是唯一的`() {
        val filterIds = FilterCatalog.ALL.map { it.id }
        assertEquals(filterIds.size, filterIds.toSet().size)
        val borderIds = BorderCatalog.ALL.map { it.id }
        assertEquals(borderIds.size, borderIds.toSet().size)
        assertTrue("原图必须在内置滤镜里", filterIds.contains(FilterCatalog.NONE_ID))
        assertTrue("无边框必须在内置边框里", borderIds.contains(BorderCatalog.NONE_ID))
    }

    @Test
    fun `明度公式应满足标准亮度权重`() {
        assertEquals(1f, LutBuilder.luma(1f, 1f, 1f), 1e-4f)
        assertEquals(0f, LutBuilder.luma(0f, 0f, 0f), 1e-4f)
        // 绿通道权重最高
        assertTrue(LutBuilder.luma(0f, 1f, 0f) > LutBuilder.luma(1f, 0f, 0f))
        assertTrue(LutBuilder.luma(1f, 0f, 0f) > LutBuilder.luma(0f, 0f, 1f))
    }
}
