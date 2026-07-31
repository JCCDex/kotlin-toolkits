plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

group = "com.jccdex.toolkits"
version = "0.1.0"

android {
    namespace = "com.jccdex.toolkits.dappconnect"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":wallet"))
    implementation(project(":did"))
    implementation(project(":webview-bridge"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
}
