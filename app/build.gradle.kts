plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.secrets.gradle.plugin)
}

android {
    namespace = "com.hmdd.simplelock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hmdd.simplelock"
        minSdk = 26
        targetSdk = 34
        // Reboot-transparency fix: register KioskHomeAlias as Device Owner's
        // persistent preferred HOME so the kiosk reliably re-engages after a
        // power cycle (the previous BOOT_COMPLETED startActivity was blocked
        // by Android 10+ BAL rules). Adds non-shortening guard to engageTimeLock
        // and an onResume recovery path in MainActivity.
        versionCode = 18
        versionName = "1.9.2"
    }

    // Stable debug signing config so every CI build is update-compatible
    // with the previously installed APK. The keystore is checked in
    // (debug only, password "android") so the signature never changes.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // For now release uses the same key so adb install -r works either way.
            signingConfig = signingConfigs.getByName("debug")
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

secrets {
    propertiesFileName = "secrets.properties"
    defaultPropertiesFileName = "local.defaults.properties"
    ignoreList.add("keyToIgnore")
    ignoreList.add("sdk.*")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
}
