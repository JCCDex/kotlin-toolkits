import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.protobuf) apply false
    alias(libs.plugins.ktlint) apply false
    jacoco
    `maven-publish`
}

jacoco {
    // Align with AGP-bundled agent resolution; prefer versions already cached locally.
    toolVersion = "0.8.13"
}

subprojects {
    apply(plugin = "jacoco")
    apply(plugin = "maven-publish")

    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }

    // Publish each Android library module so JitPack can collect artifacts.
    // JitPack invokes `publishToMavenLocal` with -Pgroup/-Pversion (e.g.
    // -Pgroup=com.github.JCCDex -Pversion=v0.2.9); read those raw values from
    // startParameter because modules hard-code their own group/version.
    // AGP 8+ does not create SoftwareComponents automatically; singleVariant
    // opts the release variant into publishing so components["release"] exists.
    afterEvaluate {
        if (pluginManager.hasPlugin("com.android.library")) {
            extensions.configure<com.android.build.gradle.LibraryExtension> {
                publishing {
                    singleVariant("release")
                }
            }
        }
    }
    tasks.withType<Test>().configureEach {
        systemProperty(
            "robolectric.dependency.repo.url",
            "https://maven.aliyun.com/repository/central",
        )
        extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
}

// Components are registered by AGP only after all projects are configured;
// create publications in projectsEvaluated so components["release"] is available.
gradle.projectsEvaluated {
    subprojects {
        if (pluginManager.hasPlugin("com.android.library")) {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["release"])
                        groupId = gradle.startParameter.projectProperties["group"] ?: "com.github.JCCDex"
                        artifactId = project.name
                        version = gradle.startParameter.projectProperties["version"] ?: project.version.toString()
                    }
                }
            }
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

val coverageModules = listOf("core", "account", "vault", "webview-bridge", "did", "nft", "wallet")
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

tasks.register("ktlintCheckAll") {
    group = "verification"
    description = "Run ktlint checks for all kotlin-toolkits modules"
    dependsOn(coverageModules.map { ":$it:ktlintCheck" })
}

tasks.register("ktlintFormatAll") {
    group = "formatting"
    description = "Auto-format Kotlin code with ktlint for all kotlin-toolkits modules"
    dependsOn(coverageModules.map { ":$it:ktlintFormat" })
}

