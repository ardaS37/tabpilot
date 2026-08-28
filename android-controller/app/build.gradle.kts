import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ardas.tabletcontroller"
    // Matches the Android SDK platform installed on this development PC.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ardas.tabletcontroller"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
}
