plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    android()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
            }
        }
    }
}

android {
    namespace = "com.example.roadcrimedetector.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        targetSdk = 34
    }
}

kotlin {
    androidTarget()

    jvmToolchain(17)
}
