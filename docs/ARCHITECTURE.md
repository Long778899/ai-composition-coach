# 技术架构（ARCHITECTURE）

## 1. 分层设计

```
┌─────────────────────────────────────────────────────┐
│  UI 层  (Compose)                                    │
│  MainActivity · OverlayView                          │
│  只消费 StateFlow<UiState>，不含任何视觉算法           │
└─────────────────────────────────────────────────────┘
                     ▲ StateFlow<Hint> / <CompositionScore>
┌─────────────────────────────────────────────────────┐
│  领域层  (纯 Kotlin，无 Android 依赖 → 可 JVM 单测)   │
│  CompositionScorer · EmaSmoother · RuleEngine · Rule │
└─────────────────────────────────────────────────────┘
                     ▲ FrameSignals
┌─────────────────────────────────────────────────────┐
│  分析层  (OpenCV / ML Kit)                            │
│  FrameAnalyzer（管线调度）                             │
│  HistogramAnalyzer · SaliencyAnalyzer · HorizonAnalyzer│
│  FaceAnalyzer · SharpnessAnalyzer                     │
└─────────────────────────────────────────────────────┘
                     ▲ Mat(224×224)
┌─────────────────────────────────────────────────────┐
│  采集层  (CameraX)                                    │
│  CameraController · FrameThrottler · DownScaler       │
└─────────────────────────────────────────────────────┘
                     ▲ 传感器
│  OrientationAnalyzer (SensorManager)                 │
```

**关键约束**：领域层**不 import 任何 `android.*` 或 `org.opencv.*`**，因此评分与规则逻辑可以在 JVM 上直接单元测试（NFR-7）。

## 2. 线程模型

| 线程 | 职责 | 说明 |
|---|---|---|
| Main | Compose 重组 / UI 绘制 | 只读 StateFlow |
| CameraX Preview Executor | 预览渲染 | 由 CameraX 内部管理 |
| `analysisExecutor`（单线程） | 抽帧 + 降采样 + 分析 + 评分 + 规则 | **串行**，避免并发争用 |
| `sensorExecutor` | 传感器回调 | 写 `AtomicReference<Float>` |
| Default（协程） | 结果平滑 + 状态推送 | EMA 在主线程外跑 |

**为什么分析用单线程串行**：分析链路极短（~17ms），串行反而避免了锁与竞态；配合 `KEEP_ONLY_LATEST`，上一帧没跑完时新帧直接丢弃。

## 3. 数据流

```
CameraX ImageAnalysis
        │  ImageProxy
        ▼
FrameThrottler.shouldAnalyze()  ──false──► proxy.close() (丢弃)
        │ true
        ▼
DownScaler.toMat(proxy, 224)    ──► 复用 Mat 池，零分配
        │ Mat 224×224 (RGBA)
        ▼
FrameAnalyzer.analyze(mat, roll, tMs)
   ├─ 快通道（每帧）: HistogramAnalyzer
   ├─ 慢通道（每 N 帧）: Saliency / Horizon / Face / Sharpness
   └─ 缓存上一次慢通道结果
        │
        ▼ FrameSignals
CompositionScorer.score(signals)  ──► CompositionScore
        │
        ▼
EmaSmoother.smooth(signals, score) ──► 平滑后 signals + score
        │
        ▼
RuleEngine.best(signals, score)    ──► Hint?（单条，带驻留约束）
        │
        ▼
MutableStateFlow<UiState>
        │
        ▼
OverlayView（Compose Canvas）渲染网格 / 主体框 / 水平线 / 提示
```

## 4. 接口定义（可插拔）

```kotlin
// 分析器统一接口：新增一种视觉信号 = 新增一个实现，不改管线
interface Analyzer {
    val cadence: Int          // 每 N 帧跑一次；1 = 每帧
    fun analyze(input: AnalysisInput, out: SignalAccumulator)
}

// 规则统一接口：纯函数
fun interface Rule {
    val id: String
    val priority: Int
    fun evaluate(signals: FrameSignals, score: CompositionScore): Hint?
}
```

## 5. 状态模型

```kotlin
data class UiState(
    val hint: Hint? = null,
    val score: CompositionScore? = null,
    val subjectPoint: PointF? = null,   // 平滑后
    val rollDegrees: Float = 0f,
    val faceRects: List<RectF> = emptyList(),
    val fps: Float = 0f,
    val latencyMs: Float = 0f,
)
```

## 6. 性能预算（每帧，中端机）

| 阶段 | 预算 | 备注 |
|---|---|---|
| ImageProxy → Mat | 3~5ms | 用 `proxy.toBitmap()` 或直接读取 Y plane |
| 降采样 | ~1ms | `INTER_AREA` |
| HistogramAnalyzer | <1ms | |
| SaliencyAnalyzer | 3~6ms | 每 3 帧 |
| FaceAnalyzer | 3~8ms | 每 2~3 帧 |
| HorizonAnalyzer | 3~5ms | 每 3 帧 |
| SharpnessAnalyzer | <1ms | |
| CompositionScorer | <0.5ms | 纯算术 |
| RuleEngine | <0.5ms | 纯算术 |
| **合计（含慢通道那一帧）** | **≈17ms** | → 可支撑 ~30Hz 分析 |

## 7. 优化手段

1. **Mat 池**：预分配 `srcSmall`、`gray`、`lap` 三个 Mat，每帧 `reuse` 而非 `new`，消除 GC 压力（NFR-2）。
2. **通道分离**：人脸检测用 320×240（精度优先），显著性用 224×224（速度优先），互不阻塞。
3. **早退**：`CompositionScore.worstAspect` 决定规则引擎只评估相关规则子集。
4. **按需 opencv**：只链接必要模块，避免 26MB 全量（NFR-5）。
5. **视觉/传感器互校**：`|roll|` 与视觉地平线差异 < 1.5° 时用传感器值（免去 Hough 开销）。

## 8. 降级策略

| 缺失能力 | 降级方案 |
|---|---|
| OpenCV `saliency` 模块未编译 | 启用 `GradientSaliencyAnalyzer`（中心先验 + 梯度能量，纯 imgproc 实现） |
| ML Kit 不可用（无 Play 服务） | 切换 `CascadeFaceAnalyzer`（OpenCV Haar） |
| 设备无 `TYPE_ROTATION_VECTOR` | 回退 `TYPE_ACCELEROMETER` |
| `ImageAnalysis` 分辨率不支持 640×480 | 取最近可用分辨率并动态调整 DownScaler 目标 |
