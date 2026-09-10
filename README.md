# Road-crime-detector-application

A Kotlin Multiplatform mobile app using Jetpack Compose for Android.

## Structure

- `app/` - Android application module with Compose UI and CameraX integration.
- `shared/` - Kotlin Multiplatform shared module with frame processing and in-memory fragment storage.

## Build

1. Open the project in Android Studio or use a local Gradle installation.
2. Build the project with Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
3. Run the app on an Android device or emulator.

## Features

- Requests camera permission.
- Streams camera frames to a Compose preview.
- Processes frame data in shared logic.
- Stores processed fragments in memory for later inspection.
