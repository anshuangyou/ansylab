import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.ansylab.vision"

    // AGP 8.13 官方只验证到 compile SDK 36.1，用 37 会刷警告。
    // 本机只装了 platform 35 和 37.0，所以取 35，既不警告也不触发下载。
    compileSdk = 35

    // 与 facefusion-mobile 保持一致：NDK r27c，本机已安装。
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.ansylab.vision"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Chaquopy 的解释器和 opencv wheel 都是原生库，必须限定 ABI。
        // 只打 arm64-v8a（目标真机 8 Gen 3）；带 x86_64 会多出约 28 MB。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 正式签名：参数放在 keystore/keystore.properties（不提交仓库）
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(
                keystoreProps.getProperty("storeFile", "keystore/ansylab.jks")
            )
            storePassword = keystoreProps.getProperty("storePassword", "")
            keyAlias = keystoreProps.getProperty("keyAlias", "ansylab")
            keyPassword = keystoreProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        // AGP 9 模板的 optimization{} 是 9.x 专属 DSL，AGP 8.13 不支持。
        // 调试阶段不需要 R8；出包时再按 8.x 写法加 proguardFiles。
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    // QNN 的 skel（libQnnHtpV**Skel.so）必须解压到磁盘才能被 HTP 加载，
    // 所以关掉默认的 extractNativeLibs=false（useLegacyPackaging = true）。
    //
    // 保留 V68/V69/V73/V75/V79/V81 六档 HTP skel 与 stub：
    //   V68=骁龙888  V69=8 Gen1/8+  V73=8 Gen2/8s Gen3
    //   V75=8 Gen3   V79=8 Elite    V81=8 Elite Gen5
    // libQnnHtp.so 会按设备 DSP 架构自动挑对应 skel，不需要手动探测。
    // DSP / GPU 后端用不到，继续排除；libQnnHtpPrepare.so（80 MB）
    // 是设备端编译图必需的，不能删。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf(
                "lib/arm64-v8a/libQnnDsp.so",
                "lib/arm64-v8a/libQnnDspV66Skel.so",
                "lib/arm64-v8a/libQnnDspV66Stub.so",
                "lib/arm64-v8a/libQnnGpu.so"
            )
        }
    }
}

// Kotlin 2.4 起 kotlinOptions.jvmTarget 是错误，必须用 compilerOptions DSL。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// ---------------------------------------------------------------------------
// Chaquopy：把 CPython 与 pip 包打进 APK
// ---------------------------------------------------------------------------
chaquopy {
    defaultConfig {
        // 必须是 3.10：Chaquopy 仓库里 opencv-python 只有 cp310 的 Android wheel。
        version = "3.10"

        pip {
            // 整棵依赖树已经预先下载到工程根目录的 wheels/，构建全程离线。
            // 用 rootProject.file 动态取路径：工程目录改名不用改这里。
            options("--find-links", rootProject.file("wheels").absolutePath)
            options("--no-index")

            install("numpy==1.23.3")
            install("opencv-python==4.5.1.48")
            // 数值 / 图像 / 配置
            install("scipy==1.8.1")
            install("pillow==11.0.0")
            install("pyyaml==6.0.3")
            // 网络 / 时间 / 姿态 / 进度
            install("requests==2.32.3")
            install("python-dateutil==2.9.0.post0")
            install("transforms3d==0.4.2")
            install("tqdm==4.67.1")
        }

        // 开发期不编译 .py 为 .pyc：异常堆栈里能看到行号。
        pyc {
            src = false
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    // 导入「程序文件夹」时用 SAF 的 DocumentFile 递归拷贝
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // USB 转串口（CH340/CH341/CH343、CP210x、FTDI、PL2303 等）
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ONNX Runtime 的 QNN EP 构建：模型在设备上由 QNN 编译到 HTP 执行，
    // 不需要离线 qnn-onnx-converter。
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
