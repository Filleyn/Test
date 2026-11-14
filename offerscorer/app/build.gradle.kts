import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// On GitHub the build signs with the private key from the repository secrets (the same one
// TrailGuide GPS uses). Android Studio builds on a PC use the PC's own debug key.
val keystorePath: String? = System.getenv("OFFERSCORER_KEYSTORE")
val keystorePassword: String? = System.getenv("OFFERSCORER_KEYSTORE_PASSWORD")
// Each GitHub build gets a higher version code so it installs over the previous one.
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.offerscorer"
    compileSdk = 35

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

    defaultConfig {
        applicationId = "com.offerscorer"
        minSdk = 29          // Android 10
        targetSdk = 35
        versionCode = 2 + buildNumber
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // The release build is only ever made by the GitHub workflow; don't let lint stop it.
    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// No runtime libraries: the app uses only what's built into Android.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
