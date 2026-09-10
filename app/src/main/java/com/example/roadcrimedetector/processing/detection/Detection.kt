package com.example.roadcrimedetector.processing.detection

data class YoloDetection(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val confidence: Float,
    val classId: Int,
    val x1: Float = x - w / 2,
    val y1: Float = y - h / 2,
    val x2: Float = x + w / 2,
    val y2: Float = y + h / 2
)
