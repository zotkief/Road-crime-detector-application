package com.example.roadcrimedetector.processing.lanes

import kotlin.math.sqrt

enum class LineType { SOLID, DASHED, UNKNOWN }

data class Point4(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val length get() = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
}

data class LineModel(
    val m: Float,
    val b: Float,
    val type: LineType,
    val segments: List<Point4>,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)
