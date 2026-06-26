// Android application module configuration
plugins {
    // Standard Android application plugin
    alias(libs.plugins.android.application)
    // Kotlin Symbol Processing (KSP) for annotation processing (e.g., Room)
    alias(libs.plugins.google.devtools.ksp)
}

android {
    // Unique identifier for the application's namespace
    namespace = "com.example.controledeponto"
    // The SDK version used to compile the application
    compileSdk = 35

    defaultConfig {
        // Unique application ID for the Play Store
        applicationId = "com.example.controledeponto"
        // Minimum Android version required to run the app (Android 8.0 Oreo)
        minSdk = 26
        // The target Android version for app behavior
        targetSdk = 35
        // Internal version number for updates
        versionCode = 82
        // User-visible version name
        versionName = "1.8.2"

        // Runner for instrumentation tests
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Disable code shrinking and obfuscation for release builds
            isMinifyEnabled = false
            // Proguard configuration files
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Java compatibility level for compilation
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // Enable ViewBinding to safely interact with UI elements
        viewBinding = true
        // Enable generation of the BuildConfig class
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        // Target JVM version for Kotlin compilation
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Core Android KTX extensions for Kotlin
    implementation(libs.androidx.core.ktx)
    // Support library for older Android versions
    implementation(libs.androidx.appcompat)
    // Material Design components
    implementation(libs.material)
    // Layout manager for flexible UI design
    implementation(libs.androidx.constraintlayout)
    // Kotlin extensions for Activity
    implementation(libs.androidx.activity.ktx)
    // Lifecycle components for ViewModel and LiveData
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    // Room persistence library for local database support
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Preference library for settings screens
    implementation(libs.androidx.preference)
    // KSP processor for Room
    ksp(libs.androidx.room.compiler)

    // Unit testing library
    testImplementation(libs.junit)
    // Android instrumentation test libraries
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
