plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("dependencyReport") {
    group = "reporting"
    description = "Generate dependency report for release"
    doLast {
        val dependencies = sortedSetOf<String>()
        subprojects {
            configurations
                .filter {
                    it.isCanBeResolved &&
                        (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath"))
                }
                .forEach { config ->
                    config.resolvedConfiguration.lenientConfiguration.firstLevelModuleDependencies.forEach { dep ->
                        dependencies += "${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}"
                    }
                }
        }
        val report = buildString {
            appendLine("# tellev Dependency Report")
            appendLine("Generated: ${java.time.LocalDate.now()}")
            appendLine()
            dependencies.forEach { appendLine("- $it") }
        }
        // The report is a build artifact: writing it to the repo root dirties the
        // tracked DEPENDENCIES.md on every run.
        val reportFile = layout.buildDirectory.file("DEPENDENCIES.md").get().asFile
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report.toString())
        println("Dependency report written to ${reportFile.path}")
    }
}
