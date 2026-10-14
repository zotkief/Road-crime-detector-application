package com.example.roadcrimedetector.model

import android.graphics.Bitmap
import com.example.roadcrimedetector.processing.tracking.TrackedVehicle

data class FrameResult(
    val bitmap: Bitmap,
    val detections: List<Detection> = emptyList(),
    val violations: List<Violation> = emptyList(),
    val trackedVehicles: Map<Int, TrackedVehicle> = emptyMap()
)

typealias ProcessedFrame = FrameResult
