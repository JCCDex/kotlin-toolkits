pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // 与下方 dependencyResolutionManagement 一致:追加 aliyun 镜像作为插件解析的兜底 ——
        // JitPack 构建环境曾出现解析不到 `org.jetbrains.kotlin.android` 插件 marker 的失败
        // (marker 本身在 MavenCentral / aliyun 都存在),加镜像后走可达源。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}

rootProject.name = "kotlin-toolkits"
include(":core")
include(":account")
include(":vault")
include(":webview-bridge")
include(":did")
include(":nft")
include(":wallet")
include(":dapp-connect")
include(":apk-verify")
include(":app-update")
