// app/build.gradle.kts

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "by.quty.launch"
    //noinspection GradleDependency
    compileSdk = 36

    defaultConfig {
        applicationId = "by.quty.launch"
        minSdk = 29
        //noinspection OldTargetApi
        targetSdk = 36
    versionCode = 227
    versionName = "0.0.85"
        versionNameSuffix = "Alpha"
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

    // Material Components (для TabLayout)
    implementation(libs.material)

    // ViewPager2
    implementation(libs.androidx.viewpager2)

    // Fragment KTX
    implementation(libs.androidx.fragment.ktx)

}



































































































































































