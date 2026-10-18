import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xfgken.icetype"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xfgken.icetype"
        // minSdk 29：剪贴板面板、InputMethodService#getSystemService 等 API 需要 Android 10。
        minSdk = 29
        // targetSdk 37 = Android 17（按用户要求）。
        targetSdk = 37
        versionCode = 13
        versionName = "1.1.10"
        // 只保留中英文资源（AGP 9 起用 androidResources.localeFilters）
        androidResources {
            localeFilters += listOf("zh", "en")
        }
        // 文档 3.1：仅打包设备 ABI 所需的原生库（librime 只为 arm64-v8a 构建）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/icetype.jks")
            storePassword = "icetype"
            keyAlias = "icetype"
            keyPassword = "icetype"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // librime.so 与自研 JNI 层由 scripts/build-native.sh 交叉编译后放入 jniLibs，
    // 不通过 Gradle/CMake 驱动（本机 aarch64，SDK 内无 cmake 组件）。
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    // Rime 资源与联想词表在 APK 内**允许 deflate 压缩**（运行时由 assets 流解压后写入私有目录），
    // 因此这里不做 noCompress —— 58MB 的词典产物与 16MB 的源词典都能显著变小。

    lint {
        abortOnError = false
        // 本机构建环境的 lint 在 proot 下偶发崩溃（与代码无关），关掉 release 期的 lint 检查。
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}