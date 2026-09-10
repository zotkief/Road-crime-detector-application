package com.example.roadcrimedetector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.example.roadcrimedetector.camera.CameraScreen
import com.example.roadcrimedetector.camera.VideoPlaybackScreen
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    companion object {
        const val TARGET_FPS = 20
        const val DEBUG_USE_VIDEO = false

        init {
            if (!OpenCVLoader.initDebug()) {
                // Handle initialization error
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            if (DEBUG_USE_VIDEO) {
                VideoPlaybackScreen(targetFps = TARGET_FPS)
                return@setContent
            }

            var permissionGranted by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }

            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {
                permissionGranted = it
            }

            LaunchedEffect(Unit) {
                if (!permissionGranted) {
                    launcher.launch(Manifest.permission.CAMERA)
                }
            }

            if (permissionGranted) {
                CameraScreen(targetFps = TARGET_FPS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Note: FrameProcessor cleanup would be handled by Compose lifecycle
    }
}