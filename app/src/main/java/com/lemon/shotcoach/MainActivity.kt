package com.lemon.shotcoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lemon.shotcoach.data.SettingsRepository
import com.lemon.shotcoach.ui.nav.AppNavHost
import com.lemon.shotcoach.ui.theme.ShotCoachTheme
import com.lemon.shotcoach.ui.theme.ShotColors
import org.opencv.android.OpenCVLoader

/**
 * 应用入口。
 *
 * == 这个类现在很薄，是刻意的 ==
 * 早期版本把「建相机 + 建分析管线 + 渲染叠加层」全写在这里，导致
 * 主界面换不掉、参数没法下发。现在只做三件事：
 *  1. 初始化 OpenCV（原生库加载必须早于任何 Mat 构造）；
 *  2. 创建全局唯一的 [SettingsRepository]（参数的持久化入口）；
 *  3. 挂上 [AppNavHost] 交给导航图。
 *
 * 相机绑定、参数下发、拍摄流程都在 `ui/screen/CameraScreen` 里，
 * 页面之间互不干扰，退出取景页会解绑相机（见 CameraScreen 的 DisposableEffect）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 OpenCV。本项目只做端侧分析，不联网。
        // 失败也不致命：构图分析会降级（人脸/地平线通道停用），相机与拍照仍可用。
        runCatching { OpenCVLoader.initLocal() }
            .onFailure { android.util.Log.w("ShotCoach", "OpenCV 初始化失败，分析能力降级", it) }

        val settings = SettingsRepository(applicationContext)

        setContent {
            ShotCoachTheme {
                Surface(
                    color = ShotColors.Black,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppNavHost(settings = settings)
                }
            }
        }
    }
}
