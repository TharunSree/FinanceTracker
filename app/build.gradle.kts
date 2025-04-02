plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    id("com.google.devtools.ksp")
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.financetracker"
    compileSdk = 35 // Or your target SDK

    buildDir = layout.buildDirectory.dir("custom_build_dir").get().asFile

    defaultConfig {
        applicationId = "com.example.financetracker"
        minSdk = 27 // Or your min SDK
        targetSdk = 35 // Or your target SDK
        versionCode = 1 // Increment as needed
        versionName = "1.0" // Increment as needed
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true // Recommended for vector drawables
        }
        // ndk {} // Keep if needed
    }


    buildTypes {
        release {
            isMinifyEnabled = false // Set to true for release builds
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    room {
        schemaDirectory(file("schemas").toString())
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true // Keep if used
        compose = true
    }

    composeOptions { // Add composeOptions if using Compose UI toolkit features
        kotlinCompilerExtensionVersion = "1.5.1" // Use appropriate version
    }

    packagingOptions { // Add packagingOptions if needed
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// app/build.gradle.kts

// ... plugins, android blocks ...

dependencies {
    // --- Core & UI ---
    implementation(libs.kotlinx.serialization.json.v130) // Use the alias defined in TOML
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material) // Uses alias from TOML
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment) // Uses alias
    implementation(libs.androidx.recyclerview) // Uses alias
    implementation(libs.androidx.databinding) // Uses alias

    // --- Lifecycle & ViewModel ---
    implementation(libs.androidx.lifecycle.runtime.ktx) // Uses alias
    implementation(libs.androidx.lifecycle.livedata) // Uses alias
    implementation(libs.androidx.lifecycle.viewmodel) // Uses alias

    // --- Room ---
    implementation(libs.androidx.room.runtime) // Uses alias
    implementation(libs.androidx.room.ktx) // Uses alias
    ksp(libs.androidx.room.compiler) // Uses alias

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.core) // Uses alias
    implementation(libs.kotlinx.coroutines.android) // Uses alias

    // --- Firebase ---
    implementation(platform(libs.firebase.bom)) // Add platform BOM **FIRST**
    implementation(libs.firebase.auth) // Uses alias (ktx implied by library name usually)
    implementation(libs.firebase.firestore) // Uses alias (ktx implied by library name usually)

    // --- Compose ---
    implementation(platform(libs.androidx.compose.bom)) // Use Compose BOM platform
    implementation(libs.androidx.activity.compose) // Uses alias
    implementation(libs.androidx.ui) // Uses alias
    implementation(libs.androidx.ui.graphics) // Uses alias
    implementation(libs.androidx.ui.tooling.preview) // Uses alias
    implementation(libs.androidx.material3) // Uses alias
    androidTestImplementation(platform(libs.androidx.compose.bom)) // Use BOM for test too
    androidTestImplementation(libs.androidx.ui.test.junit4) // Uses alias
    debugImplementation(libs.androidx.ui.tooling) // Uses alias
    debugImplementation(libs.androidx.ui.test.manifest) // Uses alias
    implementation(libs.mpandroidchart)

    // --- Gemini / AI ---
    implementation(libs.google.gemini) // Uses alias
    implementation(libs.smart.reply) // Uses alias
    implementation(libs.entity.extraction) // Uses alias

    // --- Utilities ---
    implementation(libs.google.gson) // Uses alias
    implementation(libs.github.madrapps) // Uses alias (Check if this is correct library)
    implementation(libs.androidx.preference) // Uses alias (Make sure ktx version used if needed)

    // --- Color Picker Library ---
    implementation(libs.github.dhaval) // Use the alias defined in TOML

    // --- Testing ---
    testImplementation(libs.junit) // Uses alias
    androidTestImplementation(libs.androidx.junit) // Uses alias
    androidTestImplementation(libs.androidx.espresso.core) // Uses alias

}

// apply(plugin = "com.google.gms.google-services") // Ensure this is present at the end

// apply(plugin = "com.google.gms.google-services") // Ensure this is applied
apply(plugin = "com.google.gms.google-services")