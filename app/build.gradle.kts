plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sherpa.transcript"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sherpa.transcript"
        minSdk = 26
        targetSdk = 35
        versionCode = 87
        versionName = "0.5.61"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // android.util.Log in JVM-Unit-Tests als No-Op behandeln
        // (SessionVoiceBank loggt immer, nicht nur im Debug-Modus)
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Sherpa-ONNX (AAR from libs/)
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))

    // Compose BOM (manages all Compose artifact versions)
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Apache Commons Compress (für tar.bz2-Entpackung der Speaker-Modelle)
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Unit-Tests
    testImplementation("junit:junit:4.13.2")
}
