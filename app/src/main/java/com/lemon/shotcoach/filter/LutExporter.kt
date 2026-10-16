package com.lemon.shotcoach.filter

import com.lemon.shotcoach.camera.FilterSpec
import java.util.Locale

/**
 * 把 LUT 导出成 Adobe `.cube` 文本。
 *
 * 这是本项目"滤镜 = 算法而非素材"的最后一块拼图：
 * 用户可以把任意内置滤镜导出成 .cube，拿到 DaVinci / Premiere / Photoshop
 * 里复用同一套调色 —— 参数是自造的，导出是自由的。
 */
object LutExporter {

    private const val LINE = "%.6f %.6f %.6f"

    /**
     * @param title .cube 的 TITLE 字段（调色软件里显示的名字）
     */
    fun toCubeText(lut: Lut3D, title: String): String {
        val sb = StringBuilder(lut.size * lut.size * lut.size * 26 + 256)
        sb.append("TITLE \"").append(title.replace('"', '\'')).append("\"\n")
        sb.append("# 由 AI 构图教练（ShotCoach）生成\n")
        sb.append("# 滤镜以参数形式描述，此处为烘焙后的 3D LUT\n\n")
        sb.append("LUT_3D_SIZE ").append(lut.size).append('\n')
        sb.append("DOMAIN_MIN 0.0 0.0 0.0\n")
        sb.append("DOMAIN_MAX 1.0 1.0 1.0\n\n")

        // 遍历顺序必须与 Lut3D 的内存布局一致：红轴最快，然后是绿，最后是蓝
        for (b in 0 until lut.size) {
            for (g in 0 until lut.size) {
                for (r in 0 until lut.size) {
                    val i = lut.rawIndex(r, g, b)
                    sb.append(
                        String.format(
                            Locale.US, LINE,
                            lut.data[i], lut.data[i + 1], lut.data[i + 2],
                        ),
                    ).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /**
     * 便捷方法：直接从滤镜参数导出。用于"导出当前滤镜"按钮。
     */
    fun exportFilter(spec: FilterSpec, strength: Float = 1f): String =
        toCubeText(LutBuilder.bake(spec, strength), "${spec.name} (强度 ${(strength * 100).toInt()}%)")
}
