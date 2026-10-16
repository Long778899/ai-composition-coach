# 变更记录

## v1.0.1 — 修复「建议永远只有增加曝光」

**症状**：实时构图建议几乎恒定显示「画面偏暗，增加曝光」，其余规则（水平 / 主体 / 失焦 / 人脸）几乎从不出现。

**根因**：`camera/DownScaler` 采用 letterbox（保持宽高比 + 补黑边）把画面缩到 224×224，
而 `analysis/FrameAnalyzer` 把**整个画布（含黑边）**交给了 `HistogramAnalyzer`。
黑边是纯 0 像素，在灰度直方图里被统计成「欠曝像素」：

| 画幅 | 内容区 | 黑边占比 | 实测 `clipLowRatio` | 阈值 |
|---|---|---|---|---|
| 4:3 / 3:4 | 224×168 | 25.0% | 0.25 | 0.15 |
| 16:9 | 224×126 | 43.8% | 0.44 | 0.15 |

`clipLowRatio > 0.15` 在所有画幅下**恒成立**，于是 `ExposeLowRule`（priority 95，最高）
每帧命中并压过其余全部规则。同时平均亮度被黑边拉低 25%~44%，
Laplacian 方差与 Canny 边缘也被黑边的高对比边界污染。

**修复**：
1. `DownScaler.Output` 新增 `content` —— 去掉黑边的内容子矩阵（跨帧复用，零额外分配）；
2. `FrameAnalyzer` 的全部像素统计（直方图 / 清晰度 / 地平线 / 显著性 / 人脸）改用 `content`；
3. 分析器现在直接在内容视图上产出**画面归一化坐标**，删除原先绕经 letterbox 的二次映射
   （`mapFaceRect` 及其在 `toNormalized` 上的往返）；
4. 新增回归测试：曝光正常且无裁剪时不得给出曝光建议。

> 附注：`FaceAnalyzer` 的两条路径（ML Kit / Haar 级联）在本仓库都未就绪 ——
> ML Kit 依赖未引入、`assets/` 下也没有级联文件，因此人脸通道当前恒返回空，
> 人像类建议不会出现。属设计上的"缺失即静默降级"，与本 bug 无关，但会影响建议的丰富度。

## v1.0.0 — 从「单个取景页」到完整相机

第一版只有取景页 + 构图叠加层，缺参数调节与配套页面。
本轮把整机补齐，对齐同类商业相机 App 的功能面（不含其付费与账号体系）。

### 新增：页面（11 个）

| 页面 | 文件 | 说明 |
|---|---|---|
| 启动页 | `ui/screen/SplashScreen.kt` | 矢量光圈动画（6 叶片 + 外环 + 中心十字），无 PNG |
| 首次引导 | `ui/screen/GuideScreen.kt` | 3 页：实时建议 / 参数全给 / 不联网 |
| 取景主页面 | `ui/screen/CameraScreen.kt` | 快捷条 + 变焦胶囊 + 滤镜条 + 快门排 + 参数面板入口 |
| 滤镜页 | `ui/screen/FilterScreen.kt` | 实时大图预览、强度滑杆、导出 `.cube` |
| 边框页 | `ui/screen/BorderScreen.kt` | 7 种边框，预览与导出共用渲染器 |
| 水印页 | `ui/screen/WatermarkScreen.kt` | 内容/位置/透明度/字号/颜色，预览时间每秒走动 |
| 设置页 | `ui/screen/SettingsScreen.kt` | 分析调参 + 拍摄偏好 + 格式画质 |
| 相册页 | `ui/screen/GalleryScreen.kt` | 只读本应用照片，无需相册权限；大图查看/删除 |
| 预设页 | `ui/screen/PresetScreen.kt` | 参数组合保存/应用/删除/导出 |
| 关于页 | `ui/screen/AboutScreen.kt` | 权限清单、算法拆解、与商业版差异 |
| 导航图 | `ui/nav/AppNavHost.kt` | 路由表 + `NavHost` |

### 新增：参数调节（最初缺失的核心）

`camera/CameraParams.kt` 是**全部可调项的单一数据源**，`camera/CameraParamController.kt`
负责把参数真正下发到硬件：

| 参数 | 下发目标 |
|---|---|
| 曝光补偿 EV | `CameraControl.setExposureCompensationIndex`（按硬件步长换算） |
| 变焦 | `CameraControl.setZoomRatio`（按设备支持范围钳制） |
| ISO | `Camera2Interop` → `SENSOR_SENSITIVITY` |
| 快门 | `Camera2Interop` → `SENSOR_EXPOSURE_TIME` |
| 白平衡 | `CONTROL_AWB_MODE`；自定义色温走 `COLOR_CORRECTION_GAINS` + `ColorTemp` 换算 |
| 手动对焦 | `LENS_FOCUS_DISTANCE`（屈光度） |
| 闪光 / 常亮 | `ImageCapture.flashMode` / `CameraControl.enableTorch` |
| 画幅比例 | `Preview` / `ImageCapture` 的 `setTargetAspectRatio`（变更时重建 use case） |
| 镜头朝向 | `CameraSelector.requireLensFacing`（变更时重建 use case） |

关键取舍：**只有镜头朝向与画幅变化才重建 use case**，其余参数走热更新 ——
否则拖一次 ISO 滑杆就要重开相机，体验不可接受。

### 新增：滤镜引擎（零 LUT 素材）

传统做法是内置一堆 3D LUT 贴图（每张约 1MB）。本项目把滤镜描述成
**色彩变换参数**（色温、色调、饱和、对比、分离色调、褪色、黑白…），运行时烘焙：

```
FilterSpec ──LutBuilder.bake()──► Lut3D ──┬─► buildFastTable() ─► 实时预览（整数查表）
                                          └─► sampleTrilinear()  ─► 导出成片（三线性）
```

| 文件 | 职责 |
|---|---|
| `filter/Lut3D.kt` | 3D 查找表 + 三线性采样 + 快查表打包 |
| `filter/LutBuilder.kt` | 色彩参数 → LUT（纯 Kotlin，可单测） |
| `filter/CpuLutApplier.kt` | 两条应用路径（快查表 / 三线性） |
| `filter/LutExporter.kt` | 导出 Adobe `.cube` |
| `filter/FilterEngine.kt` | 门面 + 缓存 + 缩略图 |
| `filter/SwatchPattern.kt` | 程序化色卡（缩略图兜底，不依赖相机出帧） |
| `filter/ColorTemp.kt` | 色温 → RGB 增益 |

### 新增：边框与水印（全代码绘制）

`render/BorderCatalog.kt` 定义 7 种边框（纯色宽边 / 双细线 / 圆角 / 宝丽来 /
胶片齿孔 / 内阴影框 / 无），`BorderRenderer` 用 Canvas 按输出分辨率等比绘制 ——
**所以 4K 输出不会像 1080p 素材那样发虚**。

`WatermarkRenderer` 绘制时间戳、机型、自定义文字，自动加深色描边保证可读性，
字号按短边比例缩放。水印画在照片本体内、边框之**外**不会出现。

### 新增：拍摄链路

`camera/CaptureController.kt`：定时拍摄（3s/10s，带倒计时回调）、连拍、
快门音、目标旋转同步，以及完整处理链：

```
ImageCapture ─► 按画幅居中裁切 ─► 镜像 ─► 滤镜(三线性) ─► 水印 ─► 边框 ─► 写入相册
```

全部在 `Dispatchers.Default` 上执行，主线程只收事件。

### 变更：权限与 minSdk

- `minSdk` 26 → **29**。理由：MediaStore 分区存储自 API 29 起可用，
  写自己创建的照片与下载文件**不需要任何存储权限**。若保留 28，
  保存照片就必须申请 `WRITE_EXTERNAL_STORAGE`，"只申请相机"的承诺即失效。
- 权限仍然**只有 `CAMERA`** 一项。相册读取只读本应用创建的条目（无需权限）。

### 变更：消除一处真实的不一致

原本 `UiState.GridStyle`（3 档）与 `CameraParams.GridStyle`（5 档）是两个枚举 ——
参数页能选"对角线"，叠加层却画不出来。本轮统一为单一数据源
（`UiState` 复用 `CameraParams.GridStyle`），并把网格开关、水平仪开关、
构图引导开关、评分徽标都做成可传递的 `OverlayConfig`。

### 新增：测试

`app/src/test/java/com/lemon/shotcoach/LutAndParamsTest.kt` —— 19 个用例，
覆盖 LUT 烘焙的恒等性/边界/单色/暖调、快查表与三线性的一致性、
参数编解码往返与非法值回落、色温换算、目录 ID 唯一性。

这些测试能跑在 JVM 上，是因为 `filter/`、`camera/CameraParams.kt`、
`data/CameraParamsCodec.kt` 等**完全不依赖 `android.*`**。这是刻意的分层约束。
