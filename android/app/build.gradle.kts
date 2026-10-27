import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The Worker's URL and token, and the Firebase app, from secrets.properties (gitignored).
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun secret(key: String) = "\"${secrets.getProperty(key, "").trim()}\""

android {
    namespace = "com.barclayd.muoduo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.barclayd.muoduo"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "API_BASE_URL", secret("MUODUO_API_BASE_URL"))
        buildConfigField("String", "API_TOKEN", secret("MUODUO_API_TOKEN"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", secret("FIREBASE_PROJECT_ID"))
        buildConfigField("String", "FIREBASE_APP_ID", secret("FIREBASE_APP_ID"))
        buildConfigField("String", "FIREBASE_API_KEY", secret("FIREBASE_API_KEY"))
        buildConfigField("String", "FIREBASE_SENDER_ID", secret("FIREBASE_SENDER_ID"))

        // Every phone that runs Android 12 or later is 64-bit ARM.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Play services asks for Fragment 1.1.0, which lint reads, but it resolves to 1.8.9.
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    implementation(libs.compose.icons)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.haze)
    implementation(libs.haze.glass)
    implementation(libs.haze.blur)
    implementation(libs.maplibre)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
