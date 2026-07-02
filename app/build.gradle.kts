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
        applicationId = "app.tellev"
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Release signing ────────────────────────────────────────────────
    // Credentials come from (in priority order): Gradle project properties
    // (-P on CLI / ~/.gradle/gradle.properties) → local.properties.
    // The keystore itself lives under .keystore/ (gitignored).
    fun prop(name: String): String? =
        (project.findProperty(name) as String?) ?: localProps.getProperty(name)

    val tellevStoreFile = prop("tellevStoreFile")
    val tellevStorePassword = prop("tellevStorePassword")
    val tellevKeyAlias = prop("tellevKeyAlias")
    val tellevKeyPassword = prop("tellevKeyPassword")

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
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.window)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
