plugins {
    kotlin("multiplatform") version "1.9.20" apply false
    id("com.android.application") version "8.4.2" apply false
    id("com.android.library") version "8.4.2" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
