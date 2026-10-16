# 变更记录

## v1.1.2 — 水平不再抢占唯一的提示位

**现象**（用户报）：**"水平的检测过于严格，构图建议几乎没有"**。

v1.1.1 修掉了"拍景物永远提示把主体放进画面里"之后，**提示位被另一条规则接管了** ——
这次是水平。用户看到的仍然是"什么都学不到"。

**实测**（把真代码拿到 JVM 里跑，0~10° 每 0.5° 一档，共 21 档）：

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 风景 · 地平线压正中（本该提示地平线） | 倾斜 ≤3° 给 `R_HORIZON`；**3.5°~10° 全被 `R_LEVEL` 吃掉** | 0~10° **全部** `R_HORIZON` |
| 人像 · 主体贴右边缘（本该提示移主体） | 倾斜 ≤2° 给 `R_SUBJECT_OFF`；**3.5°~8° 被 `R_LEVEL` 吃掉** | 0~8° **全部** `R_SUBJECT_OFF` |
| 21 档里构图类提示占比 | **7 / 21**（倾斜类占 14） | **21 / 21** |

**根因**（`RuleEngine` 的筛选 + 静态优先级阶梯）：

```kotlin
.filter { it.aspect == null || it.aspect == score.worstAspect || it.priority >= 90 }
val best = candidates.maxByOrNull { it.priority }   // 谁 priority 大谁说话
```

`R_LEVEL` 的 `priority = 90` 恰好命中 `>= 90` 这条**豁免通道**，于是它**无条件参赛**；
而"地平线位置"才 65、"主体位置"才 70 —— 结构上永远抢不过它。
再叠加 3° 的阈值（手持拍摄的自然晃动就在 ±3° 上下），提示位被它垄断。

### 改动

1. **阈值 3° → 5°**（`LEVEL_THRESHOLD_DEG`）。手持常态不再算"倾斜"。
2. **`R_LEVEL` 优先级 90 → 55**，不再走 `>= 90` 豁免通道，因此**只在水平确为最差子项时才参赛**；
   构图类规则（地平线 65 / 主体 70 / 人脸 60）一律优先。
   **敢降的依据**：倾斜**已经有专属的实时水平基准线**兜着（`OverlayView`，`|roll| < 1°` 变绿），
   用户随时看得到；而一行提示位是稀缺资源，应该留给构图 —— 那才是这个 App 的用途。
3. **新增 `R_LEVEL_SEVERE`（priority 92，> 10°）**：真歪得厉害时**压过构图建议**强制播报，
   文案"相机歪得厉害，先回正"。阈值取 10° 而非更小，是因为此刻水平分（每度扣 12 分）**早就扣到 0** ——
   分数与提示说的是同一件事。

### 最终行为阶梯（实测）

```
倾斜  4.5°  → （无提示）              交给实时水平线，不占提示位
倾斜  6.0°  → R_LEVEL「相机向左倾斜一点」
倾斜  9.0°  → R_LEVEL「相机向左倾斜一点」
倾斜 11.0°  → R_LEVEL_SEVERE「相机歪得厉害，先回正」   ← 压过构图建议
倾斜 15.0°  → R_LEVEL_SEVERE「相机歪得厉害，先回正」
```

### 一处**未修**的问题（如实记录）

`OrientationAnalyzer` 的注释写"`orientation[2]` = 绕 Z 轴（azimuth）"，但 Android
`SensorManager.getOrientation` 的返回是 `[azimuth, pitch, roll]` —— **下标 2 是 roll，azimuth 是下标 0**，
注释是误标。已改正文档描述，但**符号方向（提示说"向左"时设备该往哪边转）无法在本机验证**
（需要真机 + 传感器），故**没有改动代码**，留待设备在手时实测。

### 验证

```
主源码类型检查（真依赖 jar）      0 错误 / 106 class
AiCompositionTest                19/19
CompositionLogicTest             17/17
LandscapeCompositionTest         19/19   ← 本轮 +6，锁死水平不再抢占
```

---

## v1.1.1 — 修掉"拍景物永远提示把主体放进画面里"

**现象**（用户报）：拍风景时提示恒定停在 **"把主体放进画面里"**，换镜头、换角度都不变。

**定位**（把真代码拿到 JVM 里跑出来的，不是推测）：

| # | 根因 | 机理 |
|---|---|---|
| 1 | **"没测到主体"被当成"主体构图 60 分"** | `subjectScore` 兜底 60 → 扣分 (100−60)×0.40 = **16.0**，是四项里最大的 → `worstAspect` **恒为 SUBJECT** → 规则引擎只放主体类规则进场 |
| 2 | **主体类三条规则里两条返回 null** | `R_SUBJECT_OFF` 要主体坐标、`R_FACE_SMALL` 要人脸，都没有 → **只剩 `R_FACE_NONE` 命中** → 提示永远换不掉 |
| 3 | **`R_FACE_NONE` 要求画面里有人脸** | 这是**人像中心**设计。风景照本来就没脸，于是被永久判为"缺主体" |

附带发现：`horizonDegrees`（地平线倾角）算出来了，但**没有任何规则使用它的高度** —— 拍景物最要紧的那条建议（地平线放哪）**一个字都没实现**。

### 改动

1. **主体子项可以缺席。** `CompositionScore` 新增 `subjectKnown`；`FrameSignals.subjectKnown` = `hasAiSubject || saliencyConcentration >= SALIENCY_CONCENTRATION_MIN`。为 false 时 SUBJECT **整项退赛**，权重在其余子项间重新归一化 —— **只对测到的东西打分**。参赛集合与旧版一致时权重和恰为 1.0，仍走不做除法的分支，输出与 v1.0 逐位一致。
2. **删除 `R_FACE_NONE`，且不做替代。** "没测到主体"对**风景不是缺陷**（风光/建筑/静物都可以没有"主体"），给不出可执行建议时正确答案是安静，而不是永久报错。<br>注意：曾试过用"没测到主体 + 画面缺细节"的 `R_EMPTY_FRAME` 替代，实测**不可行** —— 白墙/纯天空的清晰度分极低，会先被更高优先级的 `R_BLUR`(80) 吃掉，这条规则几乎不可能触发，等于没加。
3. **新增 `R_HORIZON`（priority 65）。** 拍景物唯一真正有用的构图建议：地平线要么压上三分线（1/3，强调地面/前景），要么压下三分线（2/3，强调天空），**压在正中是最典型的"平"构图**。配套给 `HorizonAnalyzer` 加了**位置**输出（`Result.y`，归一化高度）—— 倾角只能校平，位置才能给构图建议。`aspect = null` 保证它总能参赛。
4. **地平线的位置估计用独立、更严的判据**：只认 |angle| ≤ 30° 且至少 2 条候选线，否则返回 `null`（不猜）。45° 的斜线能帮我们校平，但拿它的高度去建议"地平线压三分线"就是胡说。
5. **修掉归一化里的浮点截断 bug**（写测试时暴露）：`60.0f / 0.6000001f = 99.99998`，截断后**满分变 99** → 归一化分支改用 `roundToInt()`；权重和恰为 1.0 的分支保持截断语义，以维持逐位兼容。
6. **UI**：HUD 的主体项在未测到时显示 `—`（不再显示误导性的 60）；引导模式下画出测到的地平线（两端留缺口，与 UI 分割线区分）。

### 兼容性声明（更正 v1.1.0 的说法）

v1.1.0 写的"AI 关闭时行为与 v1.0 逐位一致"**不再成立**。精确说法：

> **主体可测**（显著性够强，或 AI 框存在）时，评分与 v1.0 逐位一致。<br>
> **主体测不到**时不再兜底 60 分 —— 这是**有意的行为变更**，因为那个 60 分正是本次死循环的根因。

对应地，v1.1.0 的 `AiCompositionTest`「AI 关闭时总分与 v1_0 手算结果一致」（断言 84）已改写为断言 100，并在测试注释里显式记录了这次变更。

### 验证

```
主源码类型检查（真依赖 jar）      0 错误 / 105 class
单测编译                          0 错误
AiCompositionTest                19/19
CompositionLogicTest             17/17
LandscapeCompositionTest         13/13   ← 新增，锁死上面三个根因
场景探针（7 种场景，真跑）        景物未装模型 → R_GOOD（不再是"把主体放进画面里"）
```

---

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
