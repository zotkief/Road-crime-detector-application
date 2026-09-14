package com.example.roadcrimedetector.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.video.VideoCapture
import androidx.camera.video.Recorder
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import android.util.Size

class CameraController(private val context: Context) {
    fun bind(
        owner: LifecycleOwner,
        preview: Preview,
        analysis: ImageAnalysis,
        videoCapture: VideoCapture<Recorder>
    ) {
        ProcessCameraProvider.getInstance(context).get().apply {
            unbindAll()
            bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, videoCapture)
        }
    }

    fun createAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
        .setTargetResolution(Size(640, 480))
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
}
