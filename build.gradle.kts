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
        val report = StringBuilder()
        report.appendLine("# tellev Dependency Report")
        report.appendLine("Generated: ${java.time.LocalDate.now()}")
        report.appendLine()
        subprojects {
            configurations.filter { it.isCanBeResolved }.forEach { config ->
                config.resolvedConfiguration.firstLevelModuleDependencies.forEach { dep ->
                    report.appendLine("- ${dep.moduleGroup}:${dep.moduleName}:${dep.moduleVersion}")
                }
            }
        }
        file("DEPENDENCIES.md").writeText(report.toString())
        println("Dependency report written to DEPENDENCIES.md")
    }
}

