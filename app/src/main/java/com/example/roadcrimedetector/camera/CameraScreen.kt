package com.example.roadcrimedetector.camera

import android.graphics.Bitmap
import androidx.camera.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.roadcrimedetector.processing.FrameProcessor
import com.example.roadcrimedetector.utils.toBitmapRGBA
import java.util.concurrent.Executors

@Composable
fun CameraScreen(targetFps: Int) {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val processor = remember { FrameProcessor(context) }
    val cameraController = remember { CameraController(context) }

    val executor = remember { Executors.newSingleThreadExecutor() }

    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastTime by remember { mutableStateOf(0L) }
    val frameInterval = 1000L / targetFps

    LaunchedEffect(Unit) {
        val preview = Preview.Builder().build()
        val analysis = cameraController.createAnalysis()

        analysis.setAnalyzer(executor) { image ->

            val now = System.currentTimeMillis()
            if (now - lastTime >= frameInterval) {
                lastTime = now

                val bitmap = image.toBitmapRGBA()
                val processedFrame = processor.process(bitmap)
                processedBitmap = processedFrame.bitmap
            }

            image.close()
        }

        cameraController.bind(lifecycleOwner, preview, analysis)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        processedBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Processed camera feed",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}