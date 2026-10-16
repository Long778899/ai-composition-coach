# AI 构图教练 · AI Composition Coach

实时取景构图分析引擎 —— **端侧神经网络推理、零联网依赖**。经典计算机视觉 + 规则引擎负责快通道，
端侧轻量模型（MediaPipe + NIMA）负责主体检测、人脸检测与美感评分，两者合成"实时 AI 构图建议"。

> **v1.1 修正**：本仓库 v1.0 的核心命题是"手机端做实时构图引导，不需要神经网络，也一个模型都不用训"。
> 实测后这条被自己推翻了 —— 伪显著性偏中心、人脸通道恒空，两个缺口靠调参补不回来。
> v1.1 起接入**端侧预训练**模型做推理（**依然不训练任何模型**：权重是别人训好的，我们只做端侧推理）。
> 完整的推翻理由与代价账单见 `docs/ALGORITHMS.md §10`。

---

## 它是怎么做到"快"的

| 手段 | 做法 | 收益 |
|---|---|---|
| **降采样** | 分析帧统一缩到 224×224 | 像素量少 **160 倍** |
| **抽帧** | 30fps 预览，每 3 帧分析 1 次 | 分析负载降 3 倍 |
| **分级触发** | 快通道（曝光/水平仪）每帧跑，慢通道（显著性/人脸/地平线）每 N 帧 | 快慢分离 |
| **经典 CV** | Haar 人脸 / 显著性 / Hough / 直方图 / Laplacian | 单帧 **毫秒级**，比神经网络快 1~2 个数量级 |
| **规则引擎** | 阈值 → 预置文案模板 | **零推理成本、零延迟** |
| **EMA 平滑** | 指数移动平均 + 驻留约束 | 提示不抖动 |

**"实时"和"AI"是两条路**：实时 = 端侧轻算法；重 AI（风格化、AI 摄影师）= 云端异步（本项目只预留接口）。

---

## 延迟预算

```
抓帧 5ms → 降采样 1ms → 人脸(224²) 3ms → 显著性+直方图 5ms
→ 评分+规则 <1ms → 平滑+UI 2ms   ≈ 17ms   → 轻松 30Hz
```

---

## 功能

- ✅ 实时曝光检测（欠曝 / 过曝）
- ✅ 实时水平检测（传感器 + 霍夫直线双通道互校）
- ✅ 主体位置分析（显著性，无需模型）
- ✅ 人脸位置引导（ML Kit / OpenCV Cascade 双实现）
- ✅ 清晰度 / 抖动检测（Laplacian 方差）
- ✅ 构图四维评分（主体 40% / 水平 25% / 曝光 20% / 清晰 15%）
- ✅ 规则引擎 → 单条最优提示 + 方向箭头
- ✅ Compose 叠加层（三分法网格 / 主体框 / 水平基准线）
- ⬜ （可选）LUT 滤镜预览

---

## 项目结构

```
app/src/main/java/com/lemon/shotcoach/
├── MainActivity.kt                 # 入口（只做 OpenCV 初始化 + 挂导航图）
├── camera/
│   ├── CameraParams.kt             # 全部可调参数的单一数据源（含各枚举/范围常量）
│   ├── CameraParamController.kt    # 参数下发（EV / 变焦 / ISO / 快门 / 白平衡 / 对焦 / 常亮）
│   ├── CameraController.kt         # CameraX 封装：取帧 → Mat → 分析；含 ImageCapture
│   ├── CaptureController.kt        # 拍照：定时 / 连拍 / 快门音 / 全链路处理
│   ├── FilterCatalog.kt            # 滤镜目录（描述为色彩参数，非素材）
│   ├── FrameThrottler.kt           # 抽帧（FR-1）
│   └── DownScaler.kt               # 降采样（FR-2）
├── analysis/
│   ├── FrameAnalyzer.kt            # 分析管线调度（快慢通道 + 叠加层配置）
│   ├── CompositionScorer.kt        # 构图评分（FR-9）
│   ├── EmaSmoother.kt              # 时间平滑（FR-10）
│   └── analyzer/
│       ├── HistogramAnalyzer.kt    # 曝光（FR-3）
│       ├── OrientationAnalyzer.kt  # 水平仪（FR-4）
│       ├── SaliencyAnalyzer.kt     # 显著性（FR-5）
│       ├── HorizonAnalyzer.kt      # 地平线（FR-6）
│       ├── FaceAnalyzer.kt         # 人脸（FR-7）
│       └── SharpnessAnalyzer.kt    # 清晰度（FR-8）
├── filter/                         # 滤镜引擎（零 LUT 素材）
│   ├── Lut3D.kt                    # 3D 查找表 + 三线性采样
│   ├── LutBuilder.kt               # 色彩参数 → LUT 烘焙（纯 Kotlin）
│   ├── CpuLutApplier.kt            # 查表应用（快查表 / 三线性两条路径）
│   ├── LutExporter.kt              # 导出 Adobe .cube
│   ├── FilterEngine.kt             # 门面 + 缩略图/预览
│   ├── SwatchPattern.kt            # 程序化色卡（缩略图兜底）
│   └── ColorTemp.kt                # 色温 → RGB 增益
├── render/                         # 成片合成（全代码绘制）
│   ├── BorderCatalog.kt            # 边框目录
│   ├── BorderRenderer.kt           # 边框绘制（7 种）
│   ├── WatermarkRenderer.kt        # 水印绘制
│   └── PhotoProcessor.kt           # 镜像 → 滤镜 → 水印 → 边框 → 保存
├── rules/
│   ├── Rule.kt                     # 规则接口
│   ├── RuleEngine.kt               # 引擎（FR-11）
│   └── BuiltinRules.kt             # 内置规则集
├── data/
│   ├── SettingsRepository.kt       # 参数 + 偏好的持久化入口
│   ├── CameraParamsCodec.kt        # 参数编解码（纯 Kotlin，可单测）
│   ├── MediaStoreSaver.kt          # 写入系统相册（无需存储权限）
│   ├── MediaQuery.kt               # 相册查询 / 缩略图 / 原图
│   ├── PreviewFrameStore.kt        # 取景帧共享（供滤镜/边框/水印实时预览）
│   └── TextFileSaver.kt            # 导出 .cube / 预设文本
├── model/
│   ├── Geometry.kt                 # 归一化点 / 矩形 / 三分法
│   ├── FrameSignals.kt             # 原始信号（分析层 ↔ 领域层契约）
│   ├── CompositionScore.kt         # 评分模型
│   ├── Hint.kt                     # 提示模型
│   └── UiState.kt                  # UI 状态 + OverlayConfig
└── ui/
    ├── OverlayView.kt              # 取景叠加层（FR-12）
    ├── theme/Theme.kt              # 暗色主题与配色
    ├── component/
    │   ├── CommonComponents.kt     # 面板/标题/滑杆/开关/跳转行等基础件
    │   ├── ParamPanel.kt           # 参数调节面板（20+ 可调项）
    │   ├── FilterStrip.kt          # 滤镜条（实时缩略图 + 强度）
    │   └── BorderStrip.kt          # 边框条（实时缩略图）
    ├── nav/AppNavHost.kt           # 路由表 + 导航图
    └── screen/
        ├── SplashScreen.kt         # 启动页（矢量光圈动画）
        ├── GuideScreen.kt          # 首次引导
        ├── CameraScreen.kt         # 取景主页面（快捷条/变焦/滤镜条/快门）
        ├── FilterScreen.kt         # 滤镜页（实时预览 + 导出 .cube）
        ├── BorderScreen.kt         # 边框页
        ├── WatermarkScreen.kt      # 水印页（时间戳实时走动）
        ├── SettingsScreen.kt       # 设置（分析调参 / 拍摄 / 格式）
        ├── GalleryScreen.kt        # 相册（仅本应用照片，无需读取权限）
        ├── PresetScreen.kt         # 参数预设
        └── AboutScreen.kt          # 关于与隐私

app/src/main/assets/README.md       # 人脸级联文件获取说明
app/src/test/java/.../CompositionLogicTest.kt   # 领域层单测
```

---

## 页面与可调参数

| 页面 | 内容 |
|---|---|
| 启动页 | 矢量光圈动画 → 首次进入走引导，否则直接进取景 |
| 引导页 | 3 页：实时建议 / 参数全给 / 不联网 |
| **取景页** | 相机预览 + 构图叠加层 + 顶部快捷条 + 变焦胶囊 + 滤镜条 + 快门排 |
| 参数面板 | 底部弹层，**不遮挡上半屏** —— 调参数时能一直看到画面变化 |
| 滤镜页 | 实时大图预览（走导出同一条三线性路径）+ 强度滑杆 + 导出 .cube |
| 边框页 | 7 种程序化边框，预览与导出共用 `BorderRenderer` |
| 水印页 | 时间戳/机型/自定义文字、位置、透明度、字号、颜色；预览时间每秒走动 |
| 设置页 | 分析调参（抽帧/平滑/驻留/人脸通道/评分徽标）+ 拍摄 + 格式画质 |
| 相册页 | 仅显示本应用拍摄的照片（**不需要相册读取权限**），点开大图、长按删除 |
| 预设页 | 参数组合保存/应用/删除/导出 |
| 关于页 | 权限清单、算法拆解、技术栈、与商业版的区别 |

### 全部可调参数（20+）

| 分类 | 参数 |
|---|---|
| 曝光 | 曝光补偿 EV（−3…+3，按 0.5 步进）、ISO（自动/50…3200）、快门（自动/1s…1/2000s） |
| 色彩 | 白平衡预设（自动/日光/阴天/阴影/白炽灯/荧光灯/自定色温 2000–8000K） |
| 对焦 | 连续 AF / 单次 AF / 手动 MF（0–10 屈光度） |
| 镜头 | 变焦 0.5×–8×（快捷 0.5/1/2/3/5）、前置/后置切换 |
| 闪光 | 关 / 自动 / 开 / 常亮 |
| 构图 | 网格（无/三分/黄金/中心/对角线）、水平仪开关、构图引导开关 |
| 画幅 | 4:3 / 16:9 / 1:1 / 3:4 |
| 其他 | 定时（关/3s/10s）、镜像（自动/开/关）、快门音、格式（JPEG/PNG/HEIC）、画质 60–100 |
| 滤镜 | 20 个内置滤镜 + 强度 0–100% |
| 边框 | 7 种边框 |
| 水印 | 开关、时间、机型、自定义文字、位置、透明度、字号、颜色 |


---

## 快速开始

### 1. 环境

- Android Studio Hedgehog+
- JDK 17
- Android SDK 34（`compileSdk` / `targetSdk` 均为 34）
- **`minSdk = 29`（Android 10）** —— 这是刻意的下限：MediaStore 分区存储从
  API 29 起可用，写自己创建的照片与下载文件**不需要任何存储权限**。
  若降到 28，保存照片就必须申请 `WRITE_EXTERNAL_STORAGE`，
  "只申请相机一项权限"的隐私承诺就守不住了。

### 2. 人脸级联文件（必需其一）

```bash
curl -L -o app/src/main/assets/haarcascade_frontalface_default.xml \
  https://raw.githubusercontent.com/opencv/opencv/4.x/data/haarcascades/haarcascade_frontalface_default.xml
```

> 未放该文件时人脸通道静默停用，其余功能照常 —— 不会崩溃。详见
> [`app/src/main/assets/README.md`](app/src/main/assets/README.md)。

### 3. OpenCV 接入

本项目依赖 OpenCV Android SDK。有两种方式：

**方式 A：Maven（推荐，社区维护包）**

```kotlin
dependencies {
    implementation("org.opencv:opencv:4.9.0")
}
```

**方式 B：官方 SDK 模块**

1. 从 https://opencv.org/releases/ 下载 Android 包
2. 在 Android Studio 中 `File > New > Import Module`，选择 `OpenCV-android-sdk/sdk`
3. 把模块名改为 `opencv`，在 `app/build.gradle.kts` 中 `implementation(project(":opencv"))`

> ⚠️ 官方 SDK 默认包含全部模块（约 26MB）。若需瘦身，参考 https://github.com/opencv/opencv 自行裁剪编译，仅保留 `core imgproc imgcodecs features2d`（可选 `saliency ximgproc`）。

### 4. 构建

仓库未内置 Gradle Wrapper 二进制（`gradlew` 的 jar 不适合入库）。首次先补齐：

```bash
gradle wrapper --gradle-version 8.4      # 或用 Android Studio 打开，会自动生成
./gradlew assembleDebug
```

### 5. 运行

连接真机（**必须真机**，模拟器无摄像头）：

```bash
./gradlew installDebug
```

---

## 验证端侧模型

```bash
# 核心三件模型应当在包里（没跑 tools/fetch-models.sh 则为空，App 仍可跑，自动退回纯 CV）
unzip -l app/build/outputs/apk/release/app-release.apk | grep -E "\.tflite$"
# 期望：efficientdet_lite0.tflite / blaze_face_short_range.tflite / nima_aesthetic_fp16.tflite

# native 运行时要一起进去，否则运行期直接崩
unzip -l app/build/outputs/apk/release/app-release.apk | grep -E "libmediapipe_tasks_jni|libtensorflowlite_jni"

# 仍然没有 INTERNET 权限 —— 模型在包内，推理全在本地，数据不出设备
grep -c "android.permission.INTERNET" app/src/main/AndroidManifest.xml
# 期望：0
```

### 没有 Android SDK 也能验证大部分代码

```bash
./tools/typecheck-no-sdk.sh
```

不用 Android SDK：直接拉真依赖 jar（MediaPipe / TFLite / OpenCV / android platform-34）
配命令行 `kotlinc`，对「模型 · 分析层 · 规则 · 渲染 · 滤镜 · AI」整块做类型检查，
并把 JVM 单元测试真正跑起来。实测 **39 个文件 0 错误、102 个 class、34 个单元测试全绿**。

它抓到过两个真实类型错误（OpenCV `Mat` 没有 `data` 成员；`meanStdDev` 需要 `MatOfDouble`），
但**不能替代 APK 构建** —— `ui/` 依赖 Compose + CameraX，不在覆盖范围内。

---

## 关键实现片段

### 抽帧 + 降采样

```kotlin
analysis.setAnalyzer(analyzerExecutor) { proxy ->
    val src = proxyToMat(proxy)                       // 复用 Mat，零每帧分配
    analyzer.analyze(src, orientation.rollDegrees(), System.currentTimeMillis())
    proxy.close()                                     // 必须在 finally 关闭
}
// FrameAnalyzer 内部：抽帧（FrameThrottler）→ 降到 224×224 → 快/慢通道
```

### 规则（纯函数，可插拔）

规则实现 `Rule` 接口 —— **新增规则不需要改动分析层**（US-07）：

```kotlin
class LevelRule(private val resolve: (String) -> String) : Rule {
    override val id = "R_LEVEL"
    override val priority = 90
    override val aspect = CompositionScore.Aspect.LEVEL

    override fun match(s: FrameSignals, c: CompositionScore): Rule.Match? {
        val roll = s.effectiveRoll
        if (abs(roll) <= 3f) return null
        // roll > 0：设备顺时针倾斜 → 提示向左回转
        return if (roll > 0f) Rule.Match("hint_level_left", Hint.Direction.LEFT)
        else Rule.Match("hint_level_right", Hint.Direction.RIGHT)
    }

    override fun textFor(m: Rule.Match) = resolve(m.textKey)   // 文案来自 strings.xml
}
```

完整规则集见 [`rules/BuiltinRules.kt`](app/src/main/java/com/lemon/shotcoach/rules/BuiltinRules.kt)。
领域层（评分 / 平滑 / 规则）**不依赖 Android**，可直接在 JVM 上单测：

```bash
./gradlew test          # 见 app/src/test/java/com/lemon/shotcoach/CompositionLogicTest.kt
```

---

## 文档

- [`项目需求文档.md`](项目需求文档.md) — 完整 PRD（功能/非功能/验收标准）
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — 技术架构与数据流
- [`docs/ALGORITHMS.md`](docs/ALGORITHMS.md) — 各算法的原理与参数
- [`docs/CHANGELOG.md`](docs/CHANGELOG.md) — 从单页取景到完整相机的变更记录

---

## 设计边界

- 本项目**只做端侧实时分析**，不采集、不上传、不落盘任何图像或人脸数据。
- 不依赖任何联网能力（`INTERNET` 权限非必需）。
- 所有算法均为公开经典方法，代码原创。
- 不含任何第三方的模型、素材、UI 或商标。
- **不含任何素材文件**：滤镜是运行时烘焙的 3D LUT（可导出 .cube），
  边框/水印/图标/启动图全部由代码绘制。全项目没有一个美术资产。
- 不设付费墙、不需要登录、不采集手机号 —— 所有功能与滤镜/边框/水印均可用。

### 权限清单（全部）

| 权限 | 用途 |
|---|---|
| `android.permission.CAMERA` | 取景与分析 |

就这一项。相册写入走 MediaStore（写自己创建的文件无需权限），
读取相册只读本应用创建的条目（同样无需权限），
导出 .cube / 预设文本走 `MediaStore.Downloads`（无需权限）。

### 与商业同类产品的差别

| | 本项目 | 典型商业相机 App |
|---|---|---|
| 实时构图建议 | 有（端侧 CV + 规则） | 有 |
| 全部参数可调 | 有（20+ 项） | 通常部分收费 |
| 滤镜 | 20 个内置，可导出 .cube | 部分收费 |
| 边框 / 水印 | 全部可用 | 部分收费 |
| 付费墙 | 无 | 有 |
| 账号 / 手机号 | 无 | 通常必需 |
| 网络权限 | 不申请 | 通常申请 |
| 素材文件 | 零 | 通常内置大量 LUT / 贴图 |

## License

MIT
