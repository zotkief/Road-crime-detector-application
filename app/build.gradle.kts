plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    
    namespace = "com.example.roadcrimedetector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.roadcrimedetector"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":sdk"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.material:material:1.5.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.4")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.camera:camera-core:1.3.3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3")
    implementation("com.google.android.material:material:1.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.00"))

    implementation("androidx.compose.material3:material3")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}