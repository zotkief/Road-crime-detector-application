package com.example.roadcrimedetector.processing.lanes

import kotlin.math.abs

object LaneLineClassifier {
    fun classify(segments: List<LineSegment>, yBottom: Float, yTop: Float): LineType {
        if (segments.isEmpty()) return LineType.OTHER
        val totalSpan = abs(yBottom - yTop)
        if (totalSpan < 1f) return LineType.OTHER

        if (segments.all { segment ->
                val dx = abs(segment.end.x - segment.start.x)
                val dy = abs(segment.end.y - segment.start.y)
                dx > 0f && dy <= dx * 0.35f
            }
        ) return LineType.TRANSVERSE

        if (segments.any { segment ->
                val dx = abs(segment.end.x - segment.start.x)
                val dy = abs(segment.end.y - segment.start.y)
                dy < 1f || dx / dy !in 0.08f..2.5f
            }
        ) return LineType.OTHER

        val slopes = segments.map { (it.end.x - it.start.x) / (it.end.y - it.start.y) }
        if (slopes.any { it > 0f } && slopes.any { it < 0f }) return LineType.OTHER

        val intervals = segments.map {
            minOf(it.start.y, it.end.y) to maxOf(it.start.y, it.end.y)
        }.sortedBy { it.first }
        var coveredSpan = 0f
        var intervalStart = intervals.first().first
        var intervalEnd = intervals.first().second
        val gaps = mutableListOf<Float>()
        for ((start, end) in intervals.drop(1)) {
            if (start <= intervalEnd) {
                intervalEnd = maxOf(intervalEnd, end)
            } else {
                coveredSpan += intervalEnd - intervalStart
                gaps += start - intervalEnd
                intervalStart = start
                intervalEnd = end
            }
        }
        coveredSpan += intervalEnd - intervalStart
        val coverageRatio = (coveredSpan / totalSpan).coerceAtMost(1f)
        val averageGap = gaps.average().toFloat()
        val regularGaps = gaps.isNotEmpty() && gaps.maxOrNull()!! <= averageGap * 2.5f
        return when {
            coverageRatio >= 0.72f -> LineType.SOLID
            coverageRatio >= 0.08f && segments.size >= 2 && regularGaps -> LineType.DASHED
            else -> LineType.OTHER
        }
    }
}
