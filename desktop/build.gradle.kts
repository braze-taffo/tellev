plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.exclude(
            "app/tellev/MainActivity.kt",
            "app/tellev/TellevComposition.kt",
            "app/tellev/TellevGraph.kt",
            "app/tellev/feature/**",
            "app/tellev/ui/**",
            "app/tellev/core/extension/WebViewJsExtensionHost.kt",
            "app/tellev/core/security/AndroidKeystoreSecretStore.kt",
        )
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}

application {
    applicationName = "tellev"
    mainClass.set("app.tellev.desktop.MainKt")
}

tasks.register<Zip>("packageWindows") {
    group = "distribution"
    description = "Build a Windows-friendly ZIP distribution with launch scripts."
    dependsOn(tasks.named("installDist"))
    from(layout.buildDirectory.dir("install/tellev"))
    archiveFileName.set("tellev-windows.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
