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

// ── Release signing ────────────────────────────────────────────────
// Credentials come from (in priority order): Gradle project properties
// (-P on CLI / ~/.gradle/gradle.properties) → local.properties.
// The keystore itself lives under .keystore/ (gitignored).
//
// Nothing here creates, prints, or stores a key: the four properties are only
// read. When any of them is missing, when the keystore is unreadable, or when
// the release signing config is therefore absent, official release packaging
// (assembleRelease / packageRelease / packageReleaseBundle /
// packageReleaseUniversalApk) fails with a non-zero exit instead of silently
// producing an unsigned — or debug-signed — artifact. debug / mini /
// mvuValidation keep their debug signature and are not gated.
fun prop(name: String): String? =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }

val tellevStoreFile = prop("tellevStoreFile")
val tellevStorePassword = prop("tellevStorePassword")
val tellevKeyAlias = prop("tellevKeyAlias")
val tellevKeyPassword = prop("tellevKeyPassword")

val releaseSigningProps: Map<String, String?> = mapOf(
    "tellevStoreFile" to tellevStoreFile,
    "tellevStorePassword" to tellevStorePassword,
    "tellevKeyAlias" to tellevKeyAlias,
    "tellevKeyPassword" to tellevKeyPassword,
)

/** Names of the signing properties that were not supplied (never their values). */
val missingReleaseSigningProps: List<String> =
    releaseSigningProps.filterValues { it.isNullOrBlank() }.keys.sorted()

/** The keystore named by `tellevStoreFile`, when a path was given at all. */
val releaseKeystoreFile: File? = tellevStoreFile?.let { rootProject.file(it) }

/** True when that keystore is an existing, readable, non-empty file. */
val releaseKeystoreReadable: Boolean =
    releaseKeystoreFile != null && releaseKeystoreFile.isFile &&
        releaseKeystoreFile.canRead() && releaseKeystoreFile.length() > 0L

/**
 * Name of the signing config actually wired into the `release` build type, or
 * null when no usable release signing config was created. Assigned while the
 * `android` block below is evaluated and re-checked by the packaging gate.
 */
var releaseBuildTypeSigningConfig: String? = null

android {
    namespace = "app.tellev"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tellev"
        minSdk = 31
        targetSdk = 36
        versionCode = 45
        versionName = "1.7.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Release signing ────────────────────────────────────────────────
    // A `release` signing config is created *only* when all four credential
    // properties were supplied and the keystore they point at is readable.
    // Leaving it absent on purpose is what makes the packaging gate below fire:
    // a release build must never be published unsigned, and a debug keystore is
    // never an acceptable substitute.
    signingConfigs {
        if (missingReleaseSigningProps.isEmpty() && releaseKeystoreReadable &&
            releaseKeystoreFile != null
        ) {
            create("release") {
                storeFile = releaseKeystoreFile
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
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
            // Only an existing release signing config is ever attached; if none
            // was created this stays null and the gate registered after the
            // android block fails the build before anything is packaged.
            signingConfig = signingConfigs.findByName("release")
            releaseBuildTypeSigningConfig = signingConfig?.name
        }
        // 体积验收包：与 release 相同的 R8/资源压缩，但用 debug 签名、
        // 独立包名（.mini），可与正式版并存安装。仅用于本地体积/功能验收，
        // 不是发布渠道；发布仍走 release + 正式签名。
        create("mini") {
            initWith(getByName("release"))
            applicationIdSuffix = ".mini"
            versionNameSuffix = "-mini"
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    testBuildType = "mvuValidation"
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("mvu-fixtures"))

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

// ── Official release packaging gate ─────────────────────────────────
// Formal (release) artifacts are the only ones users install from GitHub
// Releases, so they must be signed with the release keystore. The tasks below
// fail with a non-zero exit — before anything is packaged — when the four
// signing properties are missing, the keystore they name is unreadable, or the
// `release` build type has no signing config at all. The message names the
// missing property names only; it never prints a credential value.
//
// debug / mini / mvuValidation are local acceptance builds signed with the
// debug keystore and are deliberately not gated.
val officialReleasePackagingTaskNames = listOf(
    "assembleRelease",
    "packageRelease",
    "packageReleaseBundle",
    "packageReleaseUniversalApk",
)

/**
 * The reason official release packaging must fail right now, or null when the
 * release signing configuration is complete and usable.
 */
fun releaseSigningProblem(): String? = when {
    missingReleaseSigningProps.isNotEmpty() ->
        "release 打包缺少签名属性: ${missingReleaseSigningProps.joinToString(", ")}。" +
            "请在 local.properties（gitignored）或 Gradle 属性（-P / ~/.gradle/gradle.properties）" +
            "补齐全部四项 tellevStoreFile / tellevStorePassword / tellevKeyAlias / tellevKeyPassword。" +
            "本地验收请改用 assembleMini（同为 R8 + 资源压缩，debug 签名、.mini 包名可并存安装）或 assembleDebug。"
    releaseKeystoreFile == null ->
        "release 打包未提供 tellevStoreFile（keystore 路径）。正式包不得以未签名发布，" +
            "请在补齐四项签名属性后重试，或使用 assembleMini / assembleDebug 做本地验收。"
    !releaseKeystoreReadable ->
        "release keystore 不可读: ${releaseKeystoreFile.path}。" +
            "请确认 tellevStoreFile 指向一个存在且可读的 .jks 文件后重试；" +
            "本地验收可改用 assembleMini / assembleDebug。"
    releaseBuildTypeSigningConfig == null ->
        "release 构建类型没有可用的 signingConfig（release 签名配置不存在），" +
            "打包会得到未签名产物。请补齐签名属性与可读 keystore 后重试，" +
            "本地验收可改用 assembleMini / assembleDebug。"
    else -> null
}

/**
 * Evaluated once, after the `android` block has run (it assigns
 * [releaseBuildTypeSigningConfig]) and *not* inside any task action.
 */
val releaseGateMessage: String? = releaseSigningProblem()

// The gate is registered lazily so AGP's release tasks — which are created
// while the variant model is finalized, after this script body — are covered.
tasks.configureEach {
    if (name in officialReleasePackagingTaskNames) {
        // Copy the message into a local of this configuration action so the
        // task's action list holds a plain `String?` only. Reading a
        // script-level value from inside the task action would capture the
        // Gradle script object, and every gated task would then fail with
        // "cannot serialize Gradle script object references" when the build
        // runs with `--configuration-cache`.
        val gateMessage: String? = releaseGateMessage
        doFirst {
            gateMessage?.let { problem -> throw GradleException(problem) }
        }
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
