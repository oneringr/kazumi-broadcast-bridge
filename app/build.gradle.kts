import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingDirectory = providers.environmentVariable("KAZUMI_SIGNING_DIR").orElse("D:/存档/安卓开发").get()
val releaseSecrets = Properties().apply {
    val config = file("$signingDirectory/config.txt")
    if (config.isFile) config.inputStream().use(::load)
}

android {
    namespace = "io.github.kazumibridge"
    compileSdk = 37
    ndkVersion = "27.2.12479018"
    ndkPath = rootProject.file("tools/runtime/android-ndk-r27c").absolutePath
    defaultConfig {
        applicationId = "io.github.kazumibridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 13
        versionName = "1.0.12"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    signingConfigs {
        create("release") {
            storeFile = file("$signingDirectory/sign-key.jks")
            storePassword = releaseSecrets.getProperty("storePassword")
            keyAlias = releaseSecrets.getProperty("keyAlias")
            keyPassword = releaseSecrets.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("release") }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; prefab = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs { useLegacyPackaging = true } }
    lint { abortOnError = true }
}

dependencies {
    implementation(project(":core"))
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("com.bytedance.android:shadowhook:2.0.1")
    implementation("com.antonkarpenko:ffmpeg-kit-full-gpl:2.2.3")
    implementation("top.yukonga.miuix.kmp:miuix-android:0.8.8")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.10.3")
    implementation("org.jetbrains.compose.ui:ui:1.10.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
