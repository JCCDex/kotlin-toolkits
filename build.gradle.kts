import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.protobuf) apply false
    jacoco
}

jacoco {
    toolVersion = "0.8.11"
}

subprojects {
    apply(plugin = "jacoco")

    tasks.withType<Test>().configureEach {
        extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
}

val jacocoClassExcludes =
    listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*_Factory.*",
        "**/*_HiltModules.*",
        "**/dagger/**",
        "**/hilt_aggregated_deps/**",
        "**/databinding/**",
        "**/*Binding.*",
        "**/*.proto",
        "**/*Proto*.*",
        "**/ComposableSingletons*",
        "**/META-INF/**",
    )

val coverageModules = listOf("vault", "webview-bridge", "did", "nft", "wallet")
val coverageModuleProjects = coverageModules.map { project(":$it") }

coverageModules.forEach { moduleName ->
    tasks.register<JacocoReport>("${moduleName}JacocoReport") {
        group = "verification"
        description = "Generate JaCoCo coverage report for $moduleName"

        dependsOn(":$moduleName:testDebugUnitTest")

        val moduleProject = project(":$moduleName")
        val variant = "debug"
        val kotlinDirs = moduleProject.layout.buildDirectory.dir("tmp/kotlin-classes/$variant").get().asFile
        val javaDirs = moduleProject.layout.buildDirectory.dir("intermediates/javac/$variant/classes").get().asFile

        reports {
            html.required.set(true)
            xml.required.set(true)
            csv.required.set(false)
            html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/$moduleName/html"))
            xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/$moduleName/report.xml"))
        }

        classDirectories.setFrom(
            files(
                fileTree(kotlinDirs) { exclude(jacocoClassExcludes) },
                fileTree(javaDirs) { exclude(jacocoClassExcludes) },
            ),
        )
        sourceDirectories.setFrom(files("$moduleName/src/main/java", "$moduleName/src/main/kotlin"))
        executionData.setFrom(
            fileTree(moduleProject.layout.buildDirectory.get().asFile) {
                include(
                    "jacoco/testDebugUnitTest.exec",
                    "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                    "outputs/code-coverage/connected/**/*.ec",
                )
            }
        )
    }
}

tasks.register<JacocoReport>("jacocoAllModulesReport") {
    group = "verification"
    description = "Generate JaCoCo coverage report for all kotlin-toolkits modules"

    dependsOn(coverageModules.map { ":$it:testDebugUnitTest" })

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/allModules/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/allModules/report.xml"))
    }

    classDirectories.setFrom(
        files(
            coverageModuleProjects.flatMap { moduleProject ->
                listOf(
                    fileTree(moduleProject.layout.buildDirectory.dir("tmp/kotlin-classes/debug").get().asFile) {
                        exclude(jacocoClassExcludes)
                    },
                    fileTree(moduleProject.layout.buildDirectory.dir("intermediates/javac/debug/classes").get().asFile) {
                        exclude(jacocoClassExcludes)
                    },
                )
            }
        )
    )
    sourceDirectories.setFrom(
        files(
            coverageModules.flatMap { moduleName ->
                listOf("$moduleName/src/main/java", "$moduleName/src/main/kotlin")
            }
        )
    )
    executionData.setFrom(
        files(
            coverageModuleProjects.flatMap { moduleProject ->
                listOf(
                    moduleProject.layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"),
                    moduleProject.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
                )
            }
        )
    )
}
