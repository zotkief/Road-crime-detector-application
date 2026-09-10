package com.example.roadcrimedetector.model

data class Detection(
    val label: String,
    val confidence: Double,
    val box: FloatArray,
    val classId: Int
)
