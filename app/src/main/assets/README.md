# assets 目录说明

## 必需文件

### `haarcascade_frontalface_default.xml`

人脸检测级联文件（OpenCV 官方，**约 900KB，BSD-3 许可，可自由随 App 分发**）。

```bash
# 从 OpenCV 官方仓库获取（2.4/3.x/4.x 分支该文件基本一致）
curl -L -o haarcascade_frontalface_default.xml \
  https://raw.githubusercontent.com/opencv/opencv/4.x/data/haarcascades/haarcascade_frontalface_default.xml
```

未放入此文件时：`FaceAnalyzer.loadCascade()` 返回 null，人脸通道静默停用，
其余通道（水平/曝光/显著性/清晰度）与所有规则照常工作 —— **不会崩溃**。

### 可选：`haarcascade_frontalface_alt2.xml`

alt2 版本召回率更高、误报略多。想提高侧脸检出率可放入并用
`FaceAnalyzer` 中的文件名常量替换。

## 刻意不包含的东西

本工程**不包含**：

- ❌ 任何神经网络模型（`.tflite` / `.onnx` / `.pt` / `.param`）
- ❌ 任何 LUT 调色贴图（`.png` 3D LUT）
- ❌ 任何来自第三方产品的美术素材、文案、音效

理由：构图分析用经典 CV 即可达到目标体验（见 README），无需模型；
而第三方产品的 LUT / 美术资产是其受版权保护的表达，**技法可学，素材不可照抄**。

想加滤镜效果时，用 `androidx.camera:camera-effects` 的 `LutEffect`
加载**你自己制作**的 LUT，或参考公开的 LUT 生成算法自造。
