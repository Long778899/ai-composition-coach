# assets —— 零素材 + 端侧 AI 模型

这个目录体现两条互相制约的承诺，v1.1 起需要一起读。

## 1. 三条不变量（没有变）

**a. 没有任何美术素材。** 滤镜是色彩参数烘焙出的 LUT（`LutBuilder` 程序化生成）；
边框是几何 + 样式参数自绘；缩略图是程序化色卡；水印是 `Canvas` 绘制。
这里**不允许**出现 `.png` / `.jpg` / `.xml`（Haar 级联）/ `.cube` / 字体文件。

**b. 没有 Haar 级联 XML。** v1.0 的 `FaceAnalyzer` 留了一条 OpenCV Cascade 回退路径，
但从来没放过 `haarcascade_frontalface_default.xml` —— 所以那条路一直是死的（人脸通道恒空）。
v1.1 用端侧神经网络补上了这一环，**回退路径仍然不打算补素材文件**，见下面第 3 节。

**c. 分析全程不联网。** `AndroidManifest.xml` 只申请 `CAMERA`，没有 `INTERNET`。

## 2. 新增：端侧 AI 模型权重（v1.1）

用 `tools/fetch-models.sh` 拉到这里。**这些 `.tflite` 不进 Git 仓库**（`.gitignore` 已忽略）：
它们是第三方预训练权重，体积 0.2~6.4MB，各自有许可（见下）。

| 文件 | 用途 | 大小 | 来源 | 许可 |
|---|---|---|---|---|
| `efficientdet_lite0.tflite` | 主体检测（真检测框，取代"偏中心"的伪显著性） | 4.6 MB | MediaPipe Models · EfficientDet-Lite0 **int8** | Apache-2.0 |
| `blaze_face_short_range.tflite` | 人脸检测（取代恒空的 Haar 回退） | 229 KB | MediaPipe Models · BlazeFace short-range float16 | Apache-2.0 |
| `nima_aesthetic_fp16.tflite` | **美感评分**（唯一由模型直接给分的子项） | 6.4 MB | [litert-community/NIMA-LiteRT](https://huggingface.co/litert-community/NIMA-LiteRT) | Apache-2.0 |
| `nima_technical_fp16.tflite` | 技术质量分（可选，默认不拉） | 6.4 MB | 同上 | Apache-2.0 |

合计（核心三件）：**≈ 11.3 MB**。加上 native 运行时（arm64 单 ABI：MediaPipe ≈ 10.5MB + TFLite ≈ 3.4MB），
APK 相比 v1.0 增加约 **25 MB**。这是"要有真 AI"的实价，不打算藏。

### 为什么不用 ML Kit

ML Kit 的人脸模型**随 Google Play 服务分发**，装到没有 Play 服务的设备上就没有模型。
本项目的卖点之一是纯端侧、行为可复现，所以选 MediaPipe：权重就在 `assets/` 里，进 APK 就是确定的。

## 3. 模型缺失时的行为（重要）

`AiModels.has()` 每次启动探测一次，**模型不在不是错误**：

| 能力 | 有模型 | 没模型 |
|---|---|---|
| 主体位置 | EfficientDet 检测框中心（**测**出来的） | 显著性质心（**猜**的，偏中心） |
| 人脸 | BlazeFace 检测（可用） | Haar 回退（无 XML → 恒空，同 v1.0） |
| 美感分 | NIMA 直接给 1..10 分 | 该维度不存在，权重退回 v1.0 的 40/25/20/15（主体不可测时再归一化）|

所以：**clone 下来不跑 `fetch-models.sh` 也能构建、能跑，只是自动退回纯传统 CV。**
这条降级路径有 JVM 单测守着（`AiCompositionTest`），不会被"反正模型都有"悄悄腐蚀掉。

## 4. 验收

```bash
# AI 模型是否打进了 APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E '\.tflite$'

# native 运行时要一起进去（否则运行期直接崩）
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E 'libmediapipe_tasks_jni\.so|libtensorflowlite_jni\.so'

# 确认仍然没有传统"素材文件"混进来（PDF/图片/字体/Haar XML）
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -Ei '\.(png|jpg|jpeg|webp|xml|cube|ttf)$' | grep -v '^.*res/'
```

> v1.0 的验收命令是 `unzip -l ... | grep -E "\.(tflite|onnx|pt|mnn)$"` **必须为空**。
> v1.1 起这条**故意反转**：`G5` 与 `NFR-5` 已改写为"允许打包轻量端侧模型"，
> 见 `项目需求文档.md`。理由与代价都在 `docs/ALGORITHMS.md §10`。
