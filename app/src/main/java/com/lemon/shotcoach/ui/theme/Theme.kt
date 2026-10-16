package com.lemon.shotcoach.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 相机 App 的配色。
 *
 * 三条硬约束：
 *  1. **永远深色**：取景时屏幕越暗越不干扰测光与眼睛适应，白天黑夜都用暗色；
 *     [isSystemInDarkTheme] 故意不用 —— 相机 UI 不跟随系统浅色。
 *  2. **强调色用琥珀**：与"摄影/暖光"语汇一致，且在深色上对比度足够。
 *  3. 评分三色（好/中/差）要与强调色区分开，避免用户把"评分低"误看成"按钮高亮"。
 */
object ShotColors {
    val Black = Color(0xFF08080A)
    val Surface = Color(0xFF141419)
    val SurfaceHigh = Color(0xFF1E1E25)
    val SurfaceHigher = Color(0xFF2A2A33)

    val Accent = Color(0xFFFFC24B)
    val OnAccent = Color(0xFF241A00)

    val TextPrimary = Color(0xFFF3F3F5)
    val TextSecondary = Color(0xFF9A9AA6)
    val TextDisabled = Color(0xFF5C5C66)

    /** 评分语义色：绿=好，黄=可接受，红=需调整 */
    val ScoreGood = Color(0xFF4CD98A)
    val ScoreFair = Color(0xFFF2C14E)
    val ScorePoor = Color(0xFFE8654F)

    /** 叠加层用色（半透明，不遮挡取景） */
    val GridLine = Color(0x59FFFFFF)
    val GridLineStrong = Color(0x8CFFFFFF)
    val LevelLine = Color(0xFF6FE3B0)
    val LevelLineTilted = Color(0xFFFFC24B)
    val Arrow = Color(0xFF57C7FF)
    val SubjectBox = Color(0x99FFD166)
    val FaceBox = Color(0x99FFD166)
    val ScrimTop = Color(0xCC000000)
    val ScrimBottom = Color(0xD9000000)
}

private val ShotDarkScheme = darkColorScheme(
    primary = ShotColors.Accent,
    onPrimary = ShotColors.OnAccent,
    background = ShotColors.Black,
    onBackground = ShotColors.TextPrimary,
    surface = ShotColors.Surface,
    onSurface = ShotColors.TextPrimary,
    surfaceVariant = ShotColors.SurfaceHigh,
    onSurfaceVariant = ShotColors.TextSecondary,
    outline = ShotColors.SurfaceHigher,
)

@Composable
fun ShotCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ShotDarkScheme, content = content)
}
