package com.lemon.shotcoach

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lemon.shotcoach.analysis.FrameAnalyzer
import com.lemon.shotcoach.analysis.analyzer.FaceAnalyzer
import com.lemon.shotcoach.analysis.analyzer.OrientationAnalyzer
import com.lemon.shotcoach.camera.CameraController
import com.lemon.shotcoach.model.UiState
import com.lemon.shotcoach.rules.BuiltinRules
import com.lemon.shotcoach.ui.OverlayView
import kotlinx.coroutines.flow.MutableStateFlow
import org.opencv.android.OpenCVLoader

/**
 * 应用入口。
 *
 * 职责：申请相机权限 → 初始化 OpenCV → 组装分析管线 → 渲染 Preview + 叠加层。
 * 任何视觉算法都不在这里（见 docs/ARCHITECTURE.md §1）。
 */
class MainActivity : ComponentActivity() {

    private val uiState = MutableStateFlow(UiState())

    private var cameraController: CameraController? = null
    private var orientation: OrientationAnalyzer? = null
    private var analyzer: FrameAnalyzer? = null
    private var previewView: PreviewView? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) maybeStartCamera()
            else Toast.makeText(this, "需要相机权限才能进行构图分析", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 OpenCV。本项目只做端侧分析，不联网。
        OpenCVLoader.initLocal()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by uiState.collectAsStateWithLifecycle()

                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).also { previewView = it }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        OverlayView(state = state, modifier = Modifier.fillMaxSize())
                    }

                    // PreviewView 就绪后再绑定相机
                    LaunchedEffect(Unit) { maybeStartCamera() }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        orientation?.start()
        maybeStartCamera()
    }

    override fun onPause() {
        orientation?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        cameraController?.shutdown()
        analyzer?.release()
        super.onDestroy()
    }

    private fun maybeStartCamera() {
        if (cameraController != null) return                       // 已启动
        val pv = previewView ?: return                              // Compose 还未就绪
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val orient = OrientationAnalyzer(this).also { orientation = it }

        // 规则文案从资源注入 —— 规则本身保持无 Android 依赖，可单测
        val rules = BuiltinRules.all { key -> resolveString(key) }

        // 默认走 OpenCV Haar（不依赖 Play 服务，国内机型也稳）。
        // 启用 ML Kit：见 README，把 useMlKit 改为 true 并传入 detector。
        val faceAnalyzer = FaceAnalyzer(this, useMlKit = false)

        val fa = FrameAnalyzer(rules = rules, faceAnalyzer = faceAnalyzer).also { analyzer = it }

        val controller = CameraController(
            context = this,
            analyzer = fa,
            orientation = orient,
            onState = { s -> uiState.value = s },
            onError = { t ->
                Toast.makeText(this, "分析异常：${t.message}", Toast.LENGTH_SHORT).show()
            },
        ).also { cameraController = it }

        controller.bind(pv, this)
    }

    private fun resolveString(key: String): String = when (key) {
        "hint_level_left" -> getString(R.string.hint_level_left)
        "hint_level_right" -> getString(R.string.hint_level_right)
        "hint_level_ok" -> getString(R.string.hint_level_ok)
        "hint_expose_low" -> getString(R.string.hint_expose_low)
        "hint_expose_high" -> getString(R.string.hint_expose_high)
        "hint_subject_left" -> getString(R.string.hint_subject_left)
        "hint_subject_right" -> getString(R.string.hint_subject_right)
        "hint_subject_up" -> getString(R.string.hint_subject_up)
        "hint_subject_down" -> getString(R.string.hint_subject_down)
        "hint_face_small" -> getString(R.string.hint_face_small)
        "hint_face_none" -> getString(R.string.hint_face_none)
        "hint_blur" -> getString(R.string.hint_blur)
        "hint_good" -> getString(R.string.hint_good)
        else -> key
    }
}
