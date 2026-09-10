package com.example.roadcrimedetector.model

import android.graphics.Bitmap

data class FrameResult(
    val bitmap: Bitmap,
    val detections: List<Detection> = emptyList(),
    val violations: List<Violation> = emptyList()
)

typealias ProcessedFrame = FrameResult
