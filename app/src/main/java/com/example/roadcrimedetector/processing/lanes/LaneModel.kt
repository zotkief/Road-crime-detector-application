package com.example.roadcrimedetector.processing.lanes

import kotlin.math.sqrt

enum class LineType { SOLID, DASHED, UNKNOWN }

data class LanePoint(val x: Float, val y: Float)

data class LineSegment(val start: LanePoint, val end: LanePoint) {
    val length: Float
        get() = sqrt((end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y))
}

data class LineModel(val type: LineType, val segments: List<LineSegment>)
