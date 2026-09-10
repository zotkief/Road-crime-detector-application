package com.example.roadcrimedetector.processing.lanes

import kotlin.math.abs
import kotlin.math.sqrt

object LaneLineClassifier {
    fun classify(segments: List<Point4>, yBottom: Float, yTop: Float): LineType {
        if (segments.isEmpty()) return LineType.UNKNOWN
        val totalSpan = abs(yBottom - yTop)
        if (totalSpan < 1f) return LineType.UNKNOWN
        val coveredLength = segments.sumOf { it.length.toDouble() }.toFloat()
        val coverageRatio = coveredLength / totalSpan
        val segmentCount = segments.size
        val averageLength = coveredLength / segmentCount
        return when {
            coverageRatio > 0.75f && averageLength > totalSpan * 0.3f -> LineType.SOLID
            coverageRatio in 0.15f..0.75f && segmentCount >= 2 -> {
                val lengths = segments.map { it.length }
                val mean = lengths.average()
                val variance = lengths.sumOf { ((it - mean) * (it - mean)).toDouble() } / lengths.size
                if (sqrt(variance) < mean * 0.5) LineType.DASHED else LineType.UNKNOWN
            }
            else -> LineType.UNKNOWN
        }
    }
}
