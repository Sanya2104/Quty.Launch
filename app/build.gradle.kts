plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        versionName = "0.0.1"
        versionNameSuffix = "alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {

    // ---------- Core ----------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // ---------- Compose ----------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // ---------- Serialization ----------
    implementation(libs.kotlinx.serialization.json)

    // ---------- Web ----------
    implementation(libs.androidx.webkit)
}
