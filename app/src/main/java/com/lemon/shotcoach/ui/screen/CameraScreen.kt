package com.lemon.shotcoach.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.lemon.shotcoach.analysis.AiPipeline
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.lemon.shotcoach.analysis.FrameAnalyzer
import com.lemon.shotcoach.analysis.analyzer.FaceAnalyzer
import com.lemon.shotcoach.analysis.analyzer.OrientationAnalyzer
import com.lemon.shotcoach.camera.AspectRatio
import com.lemon.shotcoach.camera.CameraController
import com.lemon.shotcoach.camera.CameraParams
import com.lemon.shotcoach.camera.CaptureController
import com.lemon.shotcoach.camera.FilterCatalog
import com.lemon.shotcoach.camera.FlashMode
import com.lemon.shotcoach.camera.GridStyle
import com.lemon.shotcoach.camera.ZoomRange
import com.lemon.shotcoach.data.MediaQuery
import com.lemon.shotcoach.data.PreviewFrameStore
import com.lemon.shotcoach.data.SettingsRepository
import com.lemon.shotcoach.model.OverlayConfig
import com.lemon.shotcoach.model.UiState
import com.lemon.shotcoach.render.BorderCatalog
import com.lemon.shotcoach.rules.BuiltinRules
import com.lemon.shotcoach.ui.OverlayView
import com.lemon.shotcoach.ui.component.CountdownOverlay
import com.lemon.shotcoach.ui.component.FilterStrip
import com.lemon.shotcoach.ui.component.ParamPanel
import com.lemon.shotcoach.ui.component.StatusBanner
import com.lemon.shotcoach.ui.nav.Routes
import com.lemon.shotcoach.ui.theme.ShotColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 取景主页面 —— 整机的核心。
 *
 * == 版面（自上而下）==
 * ```
 * ┌──────────────────────────────────────┐
 * │ 快捷条：闪光 定时 网格 | 滤镜 边框 水印 | 参数 设置 │
 * │                                      │
 * │            取景 + 构图叠加层              │
 * │                                      │
 * │ 变焦胶囊 0.5× 1× 2×                    │
 * │ 滤镜条（横向，实时缩略图）                  │
 * │ [相册]      ● 快门     [翻转]            │
 * └──────────────────────────────────────┘
 * ```
 *
 * == 状态归属 ==
 *  - [CameraParams]：来自 [SettingsRepository]，本页只读 + 通过 [onChange] 提交
 *  - [UiState]：来自分析线程，只被 [OverlayLayer] 消费（避免整页 10Hz 重组）
 *  - 参数面板是底部弹层，**不遮挡上半屏** —— 调参数时能一直看到画面变化
 */
@Composable
fun CameraScreen(
    settings: SettingsRepository,
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // ── 权限 ───────────────────────────────────────────────
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    // ── 本地 UI 状态 ────────────────────────────────────────
    val uiState = remember { mutableStateOf(UiState()) }
    val frameSwatch = remember { mutableStateOf<Bitmap?>(null) }
    var showParams by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<StatusMsg?>(null) }
    var lastThumb by remember { mutableStateOf<Bitmap?>(null) }

    // ── 分析管线（进入本页时构建一次）────────────────────────
    // 分析调参（抽帧/平滑/驻留）在进入本页时采样；改完设置重新进本页生效。
    val tuning = remember { Triple(settings.emaAlpha, settings.analysisEveryNFrames, settings.hintDwellMs) }
    val faceEnabled = remember { settings.faceDetectEnabled }

    val stringResolver: (String) -> String = remember {
        { key ->
            val id = context.resources.getIdentifier(key, "string", context.packageName)
            if (id != 0) context.getString(id) else key
        }
    }

    val analyzer = remember {
        FrameAnalyzer(
            rules = BuiltinRules.all(stringResolver),
            faceAnalyzer = FaceAnalyzer(context),
            alpha = tuning.first,
            everyN = tuning.second,
            dwellMs = tuning.third,
            faceEnabled = faceEnabled,
            // 端侧 AI 管线（路径 1：真神经网络，全离线）。
            // 模型文件不随仓库分发；缺失时内部 available 全为 false，管线自动退回纯 CV，
            // 行为与 v1.0 逐位一致。拉模型见 tools/fetch-models.sh。
            ai = AiPipeline(context),
        )
    }
    val orientation = remember { OrientationAnalyzer(context) }
    val capture = remember { CaptureController(context) }

    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE = TextureView：与 Compose 叠加层能正确合成（SurfaceView 会盖住叠加层）
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val controller = remember {
        CameraController(
            context = context,
            analyzer = analyzer,
            orientation = orientation,
            onState = { uiState.value = it },
            onError = { /* 单帧异常不打断取景；持续异常由 capture 的 Failed 暴露 */ },
            onFrame = { bmp ->
                frameSwatch.value = bmp
                // 同时给滤镜/边框/水印页做实时预览用（内部会拷贝，取景帧本身会被复用）
                PreviewFrameStore.submit(bmp)
            },
        )
    }

    // ── 绑定 / 解绑 ────────────────────────────────────────
    DisposableEffect(granted) {
        if (granted) {
            orientation.start()
            controller.bind(previewView, lifecycleOwner, params)
            capture.bind(controller.imageCapture)
        }
        onDispose {
            orientation.stop()
            capture.release()
            controller.shutdown()
        }
    }

    // 参数变化 → 下发（镜头/画幅变化会内部重建 use case）
    LaunchedEffect(params) {
        controller.updateParams(params)
        capture.bind(controller.imageCapture)
        capture.updateTargetRotation(displayRotationDegrees(context))
    }

    // 叠加层配置 → 分析线程（跟随每帧一起推回 UI）
    LaunchedEffect(params.grid, params.levelEnabled, params.guideEnabled, settings.showScoreBadge) {
        analyzer.overlayConfig = OverlayConfig(
            gridStyle = params.grid,
            gridEnabled = params.grid != GridStyle.OFF,
            levelEnabled = params.levelEnabled,
            guideEnabled = params.guideEnabled,
            scoreEnabled = settings.showScoreBadge,
        )
    }

    // 屏幕常亮
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 新照片 → 刷新左下角相册缩略图
    LaunchedEffect(status) {
        if (status?.isError == false) {
            lastThumb = loadLatestThumb(context)
        }
    }
    LaunchedEffect(Unit) {
        lastThumb = loadLatestThumb(context)
    }

    // 状态条自动消失
    LaunchedEffect(status) {
        if (status != null) {
            delay(2200)
            status = null
        }
    }

    // 音量键当快门
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(granted) {
        if (granted) runCatching { focusRequester.requestFocus() }
    }

    val shoot: () -> Unit = {
        capture.capture(
            params = params,
            deviceLabel = deviceLabel(),
            isFront = params.lensFacing == CameraParams.LENS_FACING_FRONT,
            onCountdown = { sec -> countdown = sec },
            onEvent = { ev ->
                when (ev) {
                    is CaptureController.Event.Countdown -> countdown = ev.secondsLeft
                    CaptureController.Event.Shutter -> countdown = 0
                    CaptureController.Event.Processing -> status = StatusMsg("处理中…", false)
                    is CaptureController.Event.Saved ->
                        status = StatusMsg("已保存 ${ev.width}×${ev.height}", false)
                    is CaptureController.Event.Failed -> status = StatusMsg(ev.message, true)
                }
            },
        )
        Unit
    }

    // ── 版面 ───────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ShotColors.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { e ->
                val isVolume = e.key == Key.VolumeDown || e.key == Key.VolumeUp
                if (settings.volumeKeyShutter && isVolume && e.type == KeyEventType.KeyDown) {
                    shoot()
                    true
                } else {
                    false
                }
            },
    ) {
        if (!granted) {
            PermissionGate(
                onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // 1. 相机预览
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            // 2. 构图叠加层（只读 UiState → 不会带动整页重组）
            OverlayLayer(
                state = uiState,
                modifier = Modifier.fillMaxSize(),
            )

            // 3. 顶部快捷条
            TopQuickBar(
                params = params,
                onChange = onChange,
                onOpen = onOpen,
                onEditParams = { showParams = true },
                modifier = Modifier.align(Alignment.TopStart),
            )

            // 4. 底部：变焦 + 滤镜条 + 快门排
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                ZoomPills(
                    params = params,
                    onChange = onChange,
                )

                FilterStrip(
                    params = params,
                    onChange = onChange,
                    baseSwatch = frameSwatch.value,
                )

                Spacer(Modifier.height(6.dp))

                ShutterRow(
                    params = params,
                    lastThumb = lastThumb,
                    busy = capture.isBusy,
                    onShoot = shoot,
                    onGallery = { onOpen(Routes.GALLERY) },
                    onFlip = {
                        onChange(
                            params.copy(
                                lensFacing = if (params.lensFacing == CameraParams.LENS_FACING_BACK) {
                                    CameraParams.LENS_FACING_FRONT
                                } else {
                                    CameraParams.LENS_FACING_BACK
                                },
                            ),
                        )
                    },
                )

                Spacer(Modifier.height(12.dp))
            }

            // 5. 倒计时
            if (countdown > 0) {
                CountdownOverlay(seconds = countdown, modifier = Modifier.align(Alignment.Center))
            }

            // 6. 状态条
            status?.let { s ->
                StatusBanner(
                    text = s.text,
                    isError = s.isError,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 96.dp),
                )
            }
        }

        // 7. 参数面板（底部弹层，不遮上半屏）
        if (showParams) {
            ParamPanel(
                params = params,
                onChange = onChange,
                onDismiss = { showParams = false },
                onOpenFilter = { showParams = false; onOpen(Routes.FILTER) },
                onOpenBorder = { showParams = false; onOpen(Routes.BORDER) },
                onOpenWatermark = { showParams = false; onOpen(Routes.WATERMARK) },
                onSavePreset = {
                    showParams = false
                    onOpen(Routes.PRESETS)
                },
                onResetAll = { onChange(CameraParams()) },
            )
        }
    }
}

// ── 子组件 ──────────────────────────────────────────────────

/**
 * 叠加层宿主。
 *
 * 刻意接收 `State<UiState>` 而不是 `UiState`：这样状态读取发生**在函数内部**，
 * Compose 只会重组这一个节点（约 10Hz），而不会把整个取景页（含 AndroidView、
 * 滤镜条）一起拖下水。这是相机类界面不掉帧的关键细节。
 */
@Composable
private fun OverlayLayer(state: State<UiState>, modifier: Modifier) {
    OverlayView(state = state.value, modifier = modifier)
}

@Composable
private fun TopQuickBar(
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
    onOpen: (String) -> Unit,
    onEditParams: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickChip(
                label = when (params.flash) {
                    FlashMode.OFF -> "闪光 关"
                    FlashMode.AUTO -> "闪光 自动"
                    FlashMode.ON -> "闪光 开"
                    FlashMode.TORCH -> "常亮"
                },
                active = params.flash != FlashMode.OFF,
                onClick = {
                    val next = when (params.flash) {
                        FlashMode.OFF -> FlashMode.AUTO
                        FlashMode.AUTO -> FlashMode.ON
                        FlashMode.ON -> FlashMode.TORCH
                        FlashMode.TORCH -> FlashMode.OFF
                    }
                    onChange(params.copy(flash = next))
                },
            )
            HSpace(6)
            QuickChip(
                label = "定时 ${params.timer.label}",
                active = params.timer.seconds > 0,
                onClick = {
                    val next = com.lemon.shotcoach.camera.TimerOption.values()
                        .let { list -> list[(list.indexOf(params.timer) + 1) % list.size] }
                    onChange(params.copy(timer = next))
                },
            )
            HSpace(6)
            QuickChip(
                label = "网格 ${params.grid.label}",
                active = params.grid != GridStyle.OFF,
                onClick = {
                    val list = GridStyle.values()
                    onChange(params.copy(grid = list[(list.indexOf(params.grid) + 1) % list.size]))
                },
            )
            HSpace(6)
            QuickChip(
                label = params.aspect.label,
                active = false,
                onClick = {
                    val list = AspectRatio.values()
                    onChange(params.copy(aspect = list[(list.indexOf(params.aspect) + 1) % list.size]))
                },
            )

            Spacer(Modifier.weight(1f))

            QuickChip(label = "设置", active = false, onClick = { onOpen(Routes.SETTINGS) })
            HSpace(6)
            QuickChip(label = "参数 ⚙", active = true, onClick = onEditParams)
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickChip(
                label = "滤镜 ${FilterCatalog.byId(params.filterId).name}",
                active = params.filterId != FilterCatalog.NONE_ID,
                onClick = { onOpen(Routes.FILTER) },
            )
            HSpace(6)
            QuickChip(
                label = "边框 ${BorderCatalog.byId(params.borderId).name}",
                active = params.borderId != BorderCatalog.NONE_ID,
                onClick = { onOpen(Routes.BORDER) },
            )
            HSpace(6)
            QuickChip(
                label = "水印",
                active = params.watermark.enabled,
                onClick = { onOpen(Routes.WATERMARK) },
            )
        }
    }
}

@Composable
private fun QuickChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) Color(0xFF1A1A1A) else ShotColors.TextPrimary,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) ShotColors.Accent else Color(0x66000000))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun ZoomPills(
    params: CameraParams,
    onChange: (CameraParams) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        for (z in ZoomRange.QUICK) {
            val selected = kotlin.math.abs(params.zoomRatio - z) < 0.01f
            Text(
                text = if (z < 1f) "${z}×" else "${z.toInt()}×",
                color = if (selected) Color(0xFF1A1A1A) else ShotColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) ShotColors.Accent else Color(0x66000000))
                    .clickable { onChange(params.copy(zoomRatio = z)) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ShutterRow(
    params: CameraParams,
    lastThumb: Bitmap?,
    busy: Boolean,
    onShoot: () -> Unit,
    onGallery: () -> Unit,
    onFlip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 相册入口
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ShotColors.SurfaceHigh)
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                .clickable { onGallery() },
            contentAlignment = Alignment.Center,
        ) {
            if (lastThumb != null && !lastThumb.isRecycled) {
                Image(
                    bitmap = lastThumb.asImageBitmap(),
                    contentDescription = "相册",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(text = "相册", color = ShotColors.TextSecondary, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        // 快门
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(CircleShape)
                .border(3.dp, Color.White, CircleShape)
                .clickable(enabled = !busy) { onShoot() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (busy) ShotColors.TextDisabled else Color.White),
            )
        }

        Spacer(Modifier.weight(1f))

        // 翻转镜头
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(ShotColors.SurfaceHigh)
                .clickable { onFlip() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (params.lensFacing == CameraParams.LENS_FACING_FRONT) "后置" else "前置",
                color = ShotColors.TextPrimary,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "需要相机权限",
            color = ShotColors.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "构图引导需要读取取景画面。画面只在手机端分析，不上传、不保存。",
            color = ShotColors.TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "授予权限",
            color = Color(0xFF1A1A1A),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ShotColors.Accent)
                .clickable { onRequest() }
                .padding(horizontal = 26.dp, vertical = 12.dp),
        )
    }
}

// ── 工具 ────────────────────────────────────────────────────

private data class StatusMsg(val text: String, val isError: Boolean)

private fun deviceLabel(): String =
    "${Build.MANUFACTURER} ${Build.MODEL}".trim()

/** 屏幕当前旋转角度（度）。用于让照片方向跟随设备。 */
private fun displayRotationDegrees(context: Context): Int = runCatching {
    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: 0
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation
    }
    when (rotation) {
        android.view.Surface.ROTATION_0 -> 0
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }
}.getOrDefault(0)

private fun loadLatestThumb(context: Context): Bitmap? {
    val latest = MediaQuery.recent(context, limit = 1).firstOrNull() ?: return null
    return MediaQuery.thumbnail(context, latest.uri, 160)
}

@Composable
private fun HSpace(width: Int) {
    Spacer(Modifier.width(width.dp))
}
