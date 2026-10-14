package com.example.roadcrimedetector.processing.lanes

import kotlin.math.sqrt

enum class LineType { 
    SOLID, 
    CONTINUOUS, 
    DASHED, 
    TRANSVERSE, 
    CROSSING, 
    UNKNOWN, 
    RIGHT_CONTINOUS, 
    LEFT_CONTINOUS, 
    OTHER 
}

data class LanePoint(val x: Float, val y: Float)

data class LineSegment(val start: LanePoint, val end: LanePoint) {
    val length: Float
        get() = sqrt((end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y))
}

data class LineModel(val type: LineType, val segments: List<LineSegment>)

data class CrosswalkInfo(val yTop: Int, val yBottom: Int) {
    init {
        require(yTop >= 0 && yBottom >= yTop)
    }
}

data class PixelMask(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val lineType: LineType = LineType.OTHER
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }
}

data class ClassifiedLaneMask(
    val width: Int,
    val height: Int,
    val labels: ByteArray
) {
    init {
        require(width > 0 && height > 0)
        require(labels.size == width * height)
    }

    companion object {
        const val CONTINUOUS: Byte = 1
        const val DASHED: Byte = 2
        const val UNKNOWN: Byte = 3
        const val CROSSING: Byte = 4
        const val RIGHT_CONTINOUS: Byte = 5
        const val LEFT_CONTINOUS: Byte = 6

    }
}
