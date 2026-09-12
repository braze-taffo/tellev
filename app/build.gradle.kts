import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Load local.properties (gitignored) so release signing credentials
// can be supplied without committing them to the repo.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "app.tellev"
    compileSdk = 36

    defaultConfig {
        // 独立包名：本分支（本地生图版）与 master 正式版可同时安装、互不
        // 覆盖（签名本就不同，包名也分开后数据目录各自独立）。
        applicationId = "app.tellev.mnn"
        minSdk = 31
        targetSdk = 36
        versionCode = 29
        versionName = "1.6.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 本地生图核心（jniLibs 的 libstable_diffusion_core.so）只提供
            // arm64；过滤掉其他 ABI，避免 32 位设备装上缺库的包。
            abiFilters += listOf("arm64-v8a")
        }
    }

    // ── Release signing ────────────────────────────────────────────────
    // 本分支（mnn-image-gen）使用独立签名，与 master 发行包互不兼容、
    // 不能互换覆盖安装。凭据属性带 Mnn 前缀；缺失时宁可产出未签名包，
    // 也不回退到 master 的密钥。
    // Credentials come from (in priority order): Gradle project properties
    // (-P on CLI / ~/.gradle/gradle.properties) → local.properties.
    // The keystore itself lives under .keystore/ (gitignored).
    fun prop(name: String): String? =
        (project.findProperty(name) as String?) ?: localProps.getProperty(name)

    val tellevStoreFile = prop("tellevMnnStoreFile")
    val tellevStorePassword = prop("tellevMnnStorePassword")
    val tellevKeyAlias = prop("tellevMnnKeyAlias")
    val tellevKeyPassword = prop("tellevMnnKeyPassword")

    signingConfigs {
        if (tellevStoreFile != null && tellevStorePassword != null &&
            tellevKeyAlias != null && tellevKeyPassword != null
        ) {
            create("release") {
                storeFile = rootProject.file(tellevStoreFile)
                storePassword = tellevStorePassword
                keyAlias = tellevKeyAlias
                keyPassword = tellevKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        create("mvuValidation") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".mvuvalidation"
            matchingFallbacks += listOf("debug")
        }
        release {
            // R8 code shrinking + resource shrinking. Release APK was ~45MB with
            // minify off (full Compose/AndroidX/material-icons-extended retained).
            // With shrinking it drops to ~15-25MB. Requires the kotlinx-serialization
            // keep rules in proguard-rules.pro, otherwise R8 strips serializer() and
            // all @Serializable JSON parsing crashes at runtime.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    testBuildType = "mvuValidation"
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("mvu-fixtures"))

    // ── 本地生图核心（Local Dream MNN OpenCL）──────────────────────────
    // jniLibs 里的 libstable_diffusion_core.so 以子进程方式 exec，要求它以
    // 真实文件落在 nativeLibraryDir——必须走 legacy 打包（解压 so）。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.snakeyaml)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.window)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.1")
}
