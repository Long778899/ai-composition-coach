# AI 构图教练 · AI Composition Coach

实时取景构图分析引擎 —— **零模型文件、零联网依赖**，用经典计算机视觉 + 规则引擎实现"实时 AI 构图建议"。

> 本项目的核心命题：手机端做实时构图引导，**不需要神经网络，也一个模型都不用训**。

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
├── MainActivity.kt                 # 入口 + Compose 宿主
├── camera/
│   ├── CameraController.kt         # CameraX 封装
│   ├── FrameThrottler.kt           # 抽帧（FR-1）
│   └── DownScaler.kt               # 降采样（FR-2）
├── analysis/
│   ├── FrameAnalyzer.kt            # 分析管线调度（快慢通道）
│   ├── CompositionScorer.kt        # 构图评分（FR-9）
│   ├── EmaSmoother.kt              # 时间平滑（FR-10）
│   └── analyzer/
│       ├── HistogramAnalyzer.kt    # 曝光（FR-3）
│       ├── OrientationAnalyzer.kt  # 水平仪（FR-4）
│       ├── SaliencyAnalyzer.kt     # 显著性（FR-5）
│       ├── HorizonAnalyzer.kt      # 地平线（FR-6）
│       ├── FaceAnalyzer.kt         # 人脸（FR-7）
│       └── SharpnessAnalyzer.kt    # 清晰度（FR-8）
├── rules/
│   ├── Rule.kt                     # 规则接口
│   ├── RuleEngine.kt               # 引擎（FR-11）
│   └── BuiltinRules.kt             # 内置规则集
├── model/
│   ├── Geometry.kt                 # 归一化点 / 矩形 / 三分法
│   ├── FrameSignals.kt             # 原始信号（分析层 ↔ 领域层契约）
│   ├── CompositionScore.kt         # 评分模型
│   ├── Hint.kt                     # 提示模型
│   └── UiState.kt                  # UI 状态
└── ui/
    └── OverlayView.kt              # 取景叠加层（FR-12）

app/src/main/assets/README.md       # 人脸级联文件获取说明
app/src/test/java/.../CompositionLogicTest.kt   # 领域层单测
```

---

## 快速开始

### 1. 环境

- Android Studio Hedgehog+
- JDK 17
- Android SDK 34

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

## 验证"零模型"

```bash
unzip -l app/build/outputs/apk/release/app-release.apk | grep -E "\.(tflite|onnx|pt|mnn)$"
# 期望：无输出
```

---

## 关键实现片段

### 抽帧 + 降采样

```kotlin
analysis.setAnalyzer(analyzerExecutor) { proxy ->
    if (!throttler.shouldAnalyze()) { proxy.close(); return@setAnalyzer }
    val src = proxy.toMat()
    val small = downScaler.scale(src, 224)   // 复用 Mat，零分配
    pipeline.analyze(small, orientationSensor.roll())
    proxy.close()
}
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

---

## 设计边界

- 本项目**只做端侧实时分析**，不采集、不上传、不落盘任何图像或人脸数据。
- 不依赖任何联网能力（`INTERNET` 权限非必需）。
- 所有算法均为公开经典方法，代码原创。
- 不含任何第三方的模型、素材、UI 或商标。

## License

MIT
