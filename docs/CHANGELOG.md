# 变更记录

## v1.1.0 — 接入端侧神经网络（真 AI）

**动机**：v1.0 的 README 把"零模型文件"当卖点，但实测下来这个卖点掩盖了两个真实缺口：

| 缺口 | v1.0 表现 | 后果 |
|---|---|---|
| 伪显著性偏中心 | `SaliencyAnalyzer.spectralResidual()` 实际转调 `gradientCenterPrior(gray)`（σ=0.35·min(w,h)），并非真频谱残差 | 主体位置天然被拉向画面中心；主体在右侧三分点会被判"偏离构图" |
| 人脸通道恒空 | ML Kit 依赖被注释、`assets/` 无 Haar 级联 XML | `cachedFaces` 永远为空 → `R_FACE_SMALL`(60) / `R_FACE_NONE`(40) 永不触发，7 条内置规则只有 5 条可能命中 |

这两件事**靠调参补不回来**：不是阈值不对，是信息根本没进来。所以 v1.1 主动推翻 G5，
改为接入端侧**预训练**模型做推理（仍然不训练任何模型）。

### 新增：`analysis/ai/` 包（5 个类）

| 文件 | 职责 |
|---|---|
| `AiModels.kt` | 模型清单 + `assets/` 存在性探测（缺失即降级，不是错误） |
| `AiPipeline.kt` | 三路 AI 的唯一入口；持有缩放缓冲与 MPImage 桥，负责生命周期 |
| `MpImageBridge.kt` | RGBA `Mat` → MediaPipe `MPImage` 的零分配桥（复用 direct ByteBuffer） |
| `SubjectDetector.kt` | EfficientDet-Lite0 (int8) → 真主体框 + 类别 + 置信度 |
| `FaceDetectorAi.kt` | BlazeFace short-range → 人脸框（取代恒空的 Haar 路径） |
| `AestheticScorer.kt` | NIMA (TFLite) → 美感分 1..10，短边缩放 + 中心裁剪 + RGB 归一化 |

### 关键设计

1. **`FrameSignals` 仍是唯一契约。** 新增字段（`subjectBox` / `subjectLabel` / `subjectConfidence`
   / `facesFromAi` / `aestheticScore`）全部带默认值，且新增 `effectiveSubject`：
   AI 检测框中心优先，没有 AI 时退回显著性质心。
   评分器、`SubjectOffRule`、`FaceNoneRule`、UI 全部改读这一个字段 —— **一处切换，全局从"猜"变"测"**。
2. **两套权重，不是一套。** 有美感分时：主体 30% · 水平 18% · 曝光 15% · 清晰度 12% · 美感 25%；
   没有时逐位回到 v1.0 的 40/25/20/15。**不存在"AI 半死不活导致评分漂移"的中间态。**
3. **AI 是替换关系不是叠加关系。** 有 AI 主体检测时不再跑伪显著性；有 AI 人脸时不再跑 Haar。
   省下的算力正好抵掉一部分神经网络开销。
4. **模型缺失 = 静默降级。** `AiModels.has()` 探测不到模型 → 所有 AI 通道关闭 →
   `FrameAnalyzer.analyze()` 里三个 `want*` 恒为 false，整块零成本跳过。
   **仓库不带模型权重**（`.gitignore`），clone 下来不跑 `tools/fetch-models.sh` 也能构建、能跑，只是退回纯 CV。
5. **`EmaSmoother` 的 `null` ≠ `0`。** 美感分抽帧（每 5 帧才算一次）时输入是 `null`，
   表示"这一路没开"而不是"这一帧测得 0 分"，所以保持上一次的值而不是清零 —— 否则分数会以 5 帧为周期闪断。
6. **只用 `ByteBufferImageBuilder`，绝不用 `BitmapImageBuilder`。**
   核对 MediaPipe 字节码发现 `BitmapImageContainer.close()` 会 `bitmap.recycle()`，
   复用 Bitmap 的话第二帧就崩；而 `ByteBufferImageContainer.close()` 是空实现，可安全复用缓冲区。

### 新增规则与 UI

- `AestheticLowRule`（`R_AESTHETIC_LOW`，priority 50）：NIMA < 5.0/10 时提示
  "试试换个角度或简化背景"。**全项目唯一一条直接由神经网络输出驱动的建议。**
  通道未启用时 `aestheticScore == null` → 不触发（而不是永远触发）。
- `OverlayView`：主体框优先画 AI 检测框（四角标记样式，不含实心方框）；
  新增 `AI` 角标（仅在本帧确有神经网络通道时显示）；评分 HUD 增加"美感 xx (NIMA)"与
  `AI: 主体+人脸+美感` 明细。**角标存在的意义是可观测** —— 一眼看出 AI 是否真在工作。

### 依赖与包体

- 新增 `com.google.mediapipe:tasks-vision:0.10.35`（自带 `libmediapipe_tasks_jni.so`）
  与 `org.tensorflow:tensorflow-lite:2.16.1`（`libtensorflowlite_jni.so`，仅 NIMA 用）。
  **两个运行时 `.so` 名字不同，已核对可共存**（MediaPipe 没有回归任务 API，NIMA 只能走原生 Interpreter）。
- `androidResources { noCompress += listOf("tflite") }`：模型不压缩，省掉运行期整块解压到内存。
- `proguard-rules.pro`：`-keep com.google.mediapipe.**` 与 `org.tensorflow.lite.**`
  （两者都靠 JNI 按类名反射回调，混淆后 release 必崩）。
- 包体代价：核心三件模型 ≈ 11.3MB + native ≈ 13.9MB（arm64 单 ABI）≈ **+25MB**。

### 测试

新增 `AiCompositionTest`（纯 JVM，不碰任何 Android/MediaPipe 类），17 个用例守着三条性质：
① AI 关闭时逐位兼容 v1.0；② AI 主体框确实取代了偏中心的质心；③ AI 未启用时 AI 规则不触发。

### 验证（这次真的跑了，不是"应该没问题"）

v1.0 的遗留状态是"写完就推、从未编译过"。本轮补上了力所能及的验证手段：
`tools/typecheck-no-sdk.sh` —— 不用 Android SDK，直接拿**真依赖 jar**
（MediaPipe tasks-vision/core、TFLite + TFLite-api、OpenCV 4.9、android platform-34）
配命令行 `kotlinc` 做类型检查，并真的把 JVM 单元测试跑起来。

实测结果：

| 项目 | 结果 |
|---|---|
| 主源码子集（模型 + 分析层 + 规则 + 渲染 + 滤镜 + AI，39 个文件） | **0 错误**，产出 102 个 class |
| 测试编译 | **0 错误** |
| `AiCompositionTest` | **OK，17 个用例全绿** |
| `CompositionLogicTest`（v1.0 回归） | **OK，17 个用例全绿** |

**这套校验立刻抓出两个真实的类型错误**，都是"编译器一秒发现、人眼永远发现不了"的那类：

1. `MpImageBridge` 里写了 `mat.data` —— **OpenCV Java 的 `Mat` 没有 `data` 成员**
   （只有 `dataAddr()` 原生指针）。已改成 `mat.get(0, 0, byte[])` 批量取像素，
   数组跨帧复用，稳态依然零分配。
2. `SharpnessAnalyzer` 把 `mean`/`stddev` 声明成 `Mat`，但 `Core.meanStdDev` 的 Java
   签名要求 `MatOfDouble`。**这是 v1.0 就存在的旧错误**，因为从未编译过所以一直没暴露。已修。

其余 API 签名也都是先 `javap` 核对 AAR 字节码才写的 —— 比如发现
`BitmapImageContainer.close()` 内部会 `bitmap.recycle()`，所以必须走 `ByteBufferImageBuilder`。

### 仍未验证的部分（把边界说清楚）

- **没有出过 APK。** 本机无 Android SDK、无 GPU；`ui/`（androidx.compose + CameraX）
  与 `data/MediaStoreSaver`（androidx.exifinterface）不在类型检查覆盖范围内。
- **推理耗时全是估计值。** EfficientDet-Lite0 8~20ms、BlazeFace ~3ms、NIMA ~18ms
  来自厂商/论文口径；既没有真机，也没有跑过 TFLite 推理，未实测。
- **模型权重不随仓库分发。** 要先跑 `tools/fetch-models.sh`；不跑也能构建，
  只是自动退回纯 CV —— 这条降级路径由 `AiCompositionTest` 守着。

首次构建请按 README 补齐 gradle wrapper，在 Colab 或本机跑通后再当真。

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
