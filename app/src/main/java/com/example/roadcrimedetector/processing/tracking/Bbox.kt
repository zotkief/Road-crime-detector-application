package com.example.roadcrimedetector.processing.tracking

data class Bbox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    val cx get() = (x1 + x2) / 2
    val cy get() = (y1 + y2) / 2
}
