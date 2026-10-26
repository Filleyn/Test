plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from CI secrets so every build has the same signature
// and installs as an update over the previous one.
val keystorePath: String? = System.getenv("TRAILGUIDE_KEYSTORE")
val keystorePassword: String? = System.getenv("TRAILGUIDE_KEYSTORE_PASSWORD")

android {
    namespace = "com.trailguidegps.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trailguidegps.app"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        buildConfigField("String", "MAPS_REPO", "\"Filleyn/Test\"")
        buildConfigField("String", "MAPS_REGION", "\"cottonwood-canyon\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null && keystorePassword != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "trailguide"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // MapLibre 11.7+ reads PMTiles files directly.
    implementation("org.maplibre.gl:android-sdk:[11.7.0,12.0.0)")
}
