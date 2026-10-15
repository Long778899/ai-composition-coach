# 算法说明（ALGORITHMS）

本文档说明每个分析器背后的算法原理、参数取值与调参依据。
**所有方法均为公开经典计算机视觉算法。**

---

## 1. 降采样（DownScaler）

```kotlin
Imgproc.resize(src, dst, Size(224.0, 224.0), 0.0, 0.0, Imgproc.INTER_AREA)
```

- **为什么 224²**：像素量 50,176，相比 4K（8,294,400）少 **165 倍**。Haar 检测耗时近似与像素数线性相关，因此这一步直接决定了整个链路可行性。
- **为什么 `INTER_AREA`**：缩小图像时的最优插值，等价于区域平均，天然带抗锯齿/降噪，比 `INTER_LINEAR` 更适合。
- **保持宽高比**：预览一般为 4:3 或 16:9。直接拉伸到 224×224 会让主体坐标失真。本实现采用 **letterbox**（保留比例 + 黑边填充），并在输出坐标时反向映射回归一化位置。
  - 若追求极致性能、且只关心相对位置，可直接拉伸（误差对"三分点距离"判断影响有限，可通过阈值补偿）。

---

## 2. 曝光分析（HistogramAnalyzer）

```kotlin
Imgproc.cvtColor(src, gray, COLOR_RGBA2GRAY)
Imgproc.calcHist(listOf(gray), MatOfInt(0), mask, hist, MatOfInt(256), ranges)
```

- **平均亮度** = Σ(i · hist[i]) / N。
- **过曝占比** = (Σ_{i≥245} hist[i]) / N；**欠曝占比** = (Σ_{i≤10} hist[i]) / N。
- **判据**：
  | 条件 | 结论 |
  |---|---|
  | mean < 60 | 欠曝 |
  | mean > 200 | 过曝 |
  | clipHigh > 0.15 | 严重过曝（高光丢失） |
  | clipLow > 0.15 | 严重欠曝（暗部死黑） |
- 曝光子项分：`100 - |mean - 130| * 1.4`（130 为"适中"中心，clamp 到 0~100）。

---

## 3. 水平检测

### 3.1 传感器通道（快，~0ms）

```kotlin
// TYPE_ROTATION_VECTOR → getRotationMatrixFromVector → getOrientation
val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
```

- 优点：零计算成本、绝对角度、不受画面内容影响。
- 缺点：只反映**设备**姿态，若用户故意斜着取景（如拍对角线构图）会误报。
- 因此需要视觉通道互校（§3.2）。

### 3.2 视觉通道：霍夫直线（慢，3~5ms）

```kotlin
Imgproc.Canny(gray, edges, 50.0, 150.0)
Imgproc.HoughLinesP(edges, lines, 1.0, PI/180, threshold=60,
                    minLineLength=60.0, maxLineGap=8.0)
```

- 取长度前 5 条直线，计算各自倾角 `atan2(dy, dx)`。
- 过滤掉近垂直直线（|angle| > 60°，多为建筑/树干）。
- 剩余直线倾角取**中位数**作为视觉地平线倾角。
- **判据**：|horizonDeg| > 3° → 画面歪斜。

### 3.3 融合策略

```
if (视觉置信度足够 && |roll - horizon| < 1.5°) → 用 sensor.roll
else                                                → 用 horizon
```

---

## 4. 显著性 / 主体定位（SaliencyAnalyzer）

### 4.1 首选：OpenCV StaticSaliencySpectralResidual

```kotlin
val saliency = Saliency.createStaticSaliencySpectralResidual()
saliency.computeSaliency(srcBgr, saliencyMap)   // CV_8U 显著性图
```

- **原理**：频域残差法。对图像做 FFT → 取对数谱 → 减去局部平均对数谱得到"谱残差" → 逆 FFT 得到空间域显著性图。人类视觉系统对**非预期频段**敏感，残差即"意外区域"。
- 无需训练、无需模型、无需参数调整。

### 4.2 回退：中心先验 + 梯度能量（纯 imgproc 实现）

若 `saliency` 模块未编译：

1. **梯度能量**：`Sobel` 求 x/y 梯度，取 `sqrt(gx² + gy²)`，归一化到 0~1。
2. **中心先验**：高斯权重 `w(x,y) = exp(-((x-cx)² + (y-cy)²) / (2σ²))`，σ = 0.35 × min(w,h)。
3. **合成**：`S = 0.6 · 梯度能量 + 0.4 · 中心先验`。
4. **质心**：对 S 做阈值（> 0.6·max）后求加权质心。

```kotlin
Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0)
Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1)
Core.magnitude(gx, gy, mag)      // 梯度幅值
Core.normalize(mag, mag, 0.0, 1.0, Core.NORM_MINMAX, CvType.CV_32F)
// 与高斯中心先验逐元素相乘/相加（用 Core.multiply / Core.add）
```

### 4.3 输出

| 字段 | 含义 |
|---|---|
| `subject: PointF` | 显著性质心，归一化 0~1 |
| `saliencyConcentration` | 阈值内像素占比，反映"主体是否明确" |

- 集中度过低（< 0.02）说明画面无明确主体 → 不产生主体位置提示。

---

## 5. 人脸位置（FaceAnalyzer）

### 5.1 首选：ML Kit

```kotlin
val detector = FaceDetection.getClient(
    FaceDetectorOptions.Builder()
        .setPerformanceMode(PERFORMANCE_MODE_FAST)
        .setMinFaceSize(0.15f)
        .build()
)
```

- 端侧、约 5ms、无需打包模型文件（随 Play 服务分发）。
- 输入建议 320×240（人脸小，需要略高分辨率）。

### 5.2 回退：OpenCV Cascade

```kotlin
val cascade = CascadeClassifier(loadCascade("haarcascade_frontalface_default.xml"))
cascade.detectMultiScale(gray, faces, 1.1, 3, 0, Size(24.0, 24.0), Size())
```

- Haar 特征 + AdaBoost 级联。级联文件约 900KB，需随 APK 附带（图片资产形式放在 `assets/`，首次运行复制到 files 目录）。
- 参数：`scaleFactor = 1.1`、`minNeighbors = 3`、`minSize = (24,24)`（在 320×240 上）。

### 5.3 构图用途

- 最大人脸框中心与三分点距离 → 主体位置提示。
- 人脸宽度占画幅比 < 12% → 提示"靠近一点"。
- **仅用于构图引导**：不保存、不上传、不做身份识别。

---

## 6. 清晰度 / 抖动（SharpnessAnalyzer）

```kotlin
Imgproc.Laplacian(gray, lap, CvType.CV_64F)   // 3×3 核
Core.meanStdDev(lap, mean, stddev)
val variance = stddev.at(0.0, 0.0) * stddev.at(0.0, 0.0)
```

- **原理**：清晰的图像边缘锐利 → 二阶导数（Laplacian）响应强 → 方差大。失焦/抖动 → 高频丢失 → 方差小。这是相机自动对焦最常用的判据之一。
- **自适应阈值**（关键）：不同场景纹理差异极大（白墙 vs 树林），固定阈值必然误报。采用**滑动窗口分位数**：
  ```
  history.push(variance)          // 保留最近 60 帧
  threshold = percentile(history, 0.25)   // 低于历史 25 分位才告警
  ```
- 清晰度子项分：`min(100, variance / referenceVariance * 100)`。

---

## 7. 构图评分（CompositionScorer）

### 7.1 主体位置分

三分法：画面被 4 个交点均分。构图质量与"主体到最近三分点/中心点的距离"负相关。

```
d = min( dist(subject, P1..P4), dist(subject, center) * 1.3 )
subjectScore = clamp(100 - d * 400, 0, 100)      // d 为归一化距离（0~1）
```

- 乘 1.3 是"中心构图"的宽容系数：居中构图同样是一种合法构图法，但不如三分点"有张力"。

### 7.2 水平分

```
levelScore = clamp(100 - |roll| * 12, 0, 100)
```

- |roll| = 3° → 64 分；|roll| = 8° → 4 分。

### 7.3 曝光分

```
exposureScore = clamp(100 - |meanBrightness - 130| * 1.4, 0, 100)
```

### 7.4 清晰度分

见 §6。归一化到 0~100。

### 7.5 总分

```
total = 0.40*subject + 0.25*level + 0.20*exposure + 0.15*sharpness
```

权重依据：构图（主体位置）是手机摄影最易改进、感知最强的维度，给最高权重；清晰度用户可感知但难主动调整，给最低。

`worstAspect` = 加权扣分最大的子项 → 决定规则引擎的优先提示。

---

## 8. 时间平滑（EmaSmoother）

### 8.1 连续量：指数移动平均

```
s_t = α · x_t + (1 - α) · s_{t-1}       α = 0.35
```

- α 越大越灵敏但越抖；越小越稳但越迟钝。0.35 在 ~6Hz 分析下有约 0.5s 的时间常数，实测手感最佳。
- 对主体坐标、roll、亮度分别平滑。
- 初始化：第一帧直接取 x（避免从 0 缓慢爬升）。

### 8.2 离散量：驻留约束

```
if (newHint.id != currentHint.id && now - currentHint.shownAtMs < 800) {
    保持 currentHint          // 抑制抖动
}
```

- 同一条提示至少显示 800ms。避免"往左/往右"来回闪烁。

---

## 9. 规则引擎（RuleEngine）

- 所有规则并行求值（纯函数，无副作用），按 `priority` 降序取**第一条命中**的规则。
- 优先级设计原则：**可立刻纠正的、视觉上最刺眼的排在最前**。

| 优先级 | 规则 | 依据 |
|---|---|---|
| 95 | R_EXPOSE_LOW / HIGH（严重） | 曝光错误会让照片直接废掉 |
| 90 | R_LEVEL | 歪斜最刺眼，且一秒钟可改 |
| 80 | R_BLUR | 失焦无法事后修复 |
| 70 | R_SUBJECT_OFF | 需要移动取景，成本高 |
| 60 | R_FACE_SMALL | |
| 40 | R_FACE_NONE | 弱提示 |
| 10 | R_GOOD | 无问题时给正反馈 |

---

## 10. 参数速查表

| 参数 | 默认值 | 位置 |
|---|---|---|
| 分析分辨率 | 224×224（人脸通道 320×240） | `DownScaler` |
| 抽帧间隔 | 3 | `FrameThrottler` |
| 快通道频率 | 每帧 | `HistogramAnalyzer.cadence = 1` |
| 显著性频率 | 每 3 帧 | `SaliencyAnalyzer.cadence = 3` |
| 人脸频率 | 每 2 帧 | `FaceAnalyzer.cadence = 2` |
| 地平线频率 | 每 3 帧 | `HorizonAnalyzer.cadence = 3` |
| EMA α | 0.35 | `EmaSmoother` |
| 提示驻留 | 800ms | `RuleEngine` |
| 水平告警阈值 | 3° | `R_LEVEL` |
| 欠曝阈值 | mean < 60 | `R_EXPOSE_LOW` |
| 过曝阈值 | mean > 200 | `R_EXPOSE_HIGH` |
| 主体偏移阈值 | 15% 画幅 | `R_SUBJECT_OFF` |
| 人脸过小阈值 | 12% 画幅 | `R_FACE_SMALL` |
