plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lemon.shotcoach"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lemon.shotcoach"
        // minSdk 29（Android 10）是刻意的下限，不是随手写的：
        // MediaStore 分区存储从 API 29 起可用，写自己创建的照片/下载文件
        // **不需要任何存储权限**。若降到 28，保存照片就必须申请
        // WRITE_EXTERNAL_STORAGE，"只申请相机一项权限"的隐私承诺就破了。
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.1.2"

        // 本项目只做端侧分析，不需要任何网络能力
        // 不申请 INTERNET 权限，见 AndroidManifest.xml
        // v1.1 接入端侧神经网络后这条**依旧成立**：模型在 assets 里，推理全在本地。

        ndk {
            // 只保留 arm64，减小包体（OpenCV / MediaPipe / TFLite 都是多 ABI 大头）
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    androidResources {
        // 端侧模型不压缩：省掉运行期整块解压到内存的一步，也让 APK 内条目可直接 mmap。
        // （MediaPipe 走 AssetManager 也能读压缩条目，但那会多一份模型大小的常驻内存。）
        noCompress += listOf("tflite")
    }
}

dependencies {
    val camerax = "1.3.1"

    // CameraX
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // 基础
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 导航（Splash → Guide → Camera → Filter/Border/Watermark/Settings/Gallery）
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // EXIF 写入（拍摄参数落盘，参数调节闭环的最后一步）
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OpenCV（二选一：Maven 版 或 本地 :opencv 模块）
    // 用本地模块时改成 implementation(project(":opencv"))
    implementation("org.opencv:opencv:4.9.0")

    // ── 端侧 AI 推理（v1.1 路径 1：真神经网络，全离线，不联网）──────────
    //
    // MediaPipe Tasks Vision：目标检测（真主体框）+ 人脸检测。
    // 自带 native 运行时 libmediapipe_tasks_jni.so；模型由 assets 提供。
    // 为什么不用 ML Kit：ML Kit 人脸模型随 Play 服务分发，离线纯端侧场景不可控；
    // MediaPipe 把权重放 assets，行为完全可复现。
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // TFLite 运行时：只有 NIMA 美感/技术质量评分用（MediaPipe 没有回归任务 API）。
    // 它的 native 库是 libtensorflowlite_jni.so —— 与 libmediapipe_tasks_jni.so **名字不同**，
    // 两个运行时可以共存（已核对两边的 .so 清单，无同名冲突）。
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // 可选：GPU delegate。Adreno/Mali 上主体检测可再快 2~3 倍。
    // 启用后需把 AiPipeline 里的 Delegate.CPU 换成 GPU，并保留 CPU 回退（低端机会失败）。
    // implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")

    // 测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
