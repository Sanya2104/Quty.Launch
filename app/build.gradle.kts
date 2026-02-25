plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "by.quty.launch"
    compileSdk = 36

    defaultConfig {
        applicationId = "by.quty.launch"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.2"
        versionNameSuffix = "alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // WebKit
    implementation(libs.androidx.webkit)
}