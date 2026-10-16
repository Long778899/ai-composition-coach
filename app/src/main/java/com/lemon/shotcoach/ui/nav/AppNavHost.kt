package com.lemon.shotcoach.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.data.SettingsRepository
import com.lemon.shotcoach.ui.screen.AboutScreen
import com.lemon.shotcoach.ui.screen.BorderScreen
import com.lemon.shotcoach.ui.screen.CameraScreen
import com.lemon.shotcoach.ui.screen.FilterScreen
import com.lemon.shotcoach.ui.screen.GalleryScreen
import com.lemon.shotcoach.ui.screen.GuideScreen
import com.lemon.shotcoach.ui.screen.PresetScreen
import com.lemon.shotcoach.ui.screen.SettingsScreen
import com.lemon.shotcoach.ui.screen.SplashScreen
import com.lemon.shotcoach.ui.screen.WatermarkScreen

/**
 * 路由表。
 *
 * 与 APK 侦察到的 Activity 链一一对应（Splash → Guide → Main(相机) →
 * Settings / PhotoBorder / Watermark / VipPurchase），只是把内购/兑换码
 * 那两条换成了**参数、滤镜、预设**——这是本项目与商业版定位的差别所在：
 * 能力全给，不设付费墙。
 */
object Routes {
    const val SPLASH = "splash"
    const val GUIDE = "guide"
    const val CAMERA = "camera"
    const val FILTER = "filter"
    const val BORDER = "border"
    const val WATERMARK = "watermark"
    const val SETTINGS = "settings"
    const val GALLERY = "gallery"
    const val PRESETS = "presets"
    const val ABOUT = "about"
}

/**
 * 全局导航图。
 *
 * 参数状态**只在最外层订阅一次**（[SettingsRepository.params]），再作为
 * 不可变值向下传 —— 每个页面拿到的都是同一个快照，不会出现"设置页改了、
 * 相机页还是旧值"的错位。
 */
@Composable
fun AppNavHost(
    settings: SettingsRepository,
    navController: NavHostController = rememberNavController(),
) {
    val params by settings.params.collectAsState()
    val onChange: (CameraParams) -> Unit = { next -> settings.update { next } }

    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    val next = if (settings.isFirstRun()) Routes.GUIDE else Routes.CAMERA
                    navController.navigate(next) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.GUIDE) {
            GuideScreen(
                onStart = {
                    settings.markGuideDone()
                    navController.navigate(Routes.CAMERA) {
                        popUpTo(Routes.GUIDE) { inclusive = true }
                    }
                },
                onSkip = {
                    settings.markGuideDone()
                    navController.navigate(Routes.CAMERA) {
                        popUpTo(Routes.GUIDE) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.CAMERA) {
            CameraScreen(
                settings = settings,
                params = params,
                onChange = onChange,
                onOpen = { route -> navController.navigate(route) },
            )
        }

        composable(Routes.FILTER) {
            FilterScreen(
                params = params,
                onChange = onChange,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.BORDER) {
            BorderScreen(
                params = params,
                onChange = onChange,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.WATERMARK) {
            WatermarkScreen(
                params = params,
                onChange = onChange,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = settings,
                params = params,
                onChange = onChange,
                onBack = { navController.popBackStack() },
                onOpenPresets = { navController.navigate(Routes.PRESETS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }

        composable(Routes.GALLERY) {
            GalleryScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.PRESETS) {
            PresetScreen(
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
