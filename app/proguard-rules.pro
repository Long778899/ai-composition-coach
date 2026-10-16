# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ── 端侧 AI（v1.1：MediaPipe Tasks + TensorFlow Lite）─────────────
#
# 这两个库都通过 JNI 在 native 侧按**类名字符串**回调 Java 对象，
# 混淆器看不到这种引用，会把类删掉或改名 → 运行期直接 ClassNotFoundException。
# 只在实际启用 minify 的 release 构建里才会暴露，所以这里必须显式 keep。

# MediaPipe Tasks（目标检测 / 人脸检测）
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# TensorFlow Lite（NIMA 美感评分用 Interpreter）
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn org.tensorflow.lite.gpu.**

# 上面两库的传递依赖里常见的"编译期才用到"的注解/生成代码
-dontwarn com.google.auto.value.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
