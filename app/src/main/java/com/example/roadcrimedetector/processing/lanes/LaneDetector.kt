package com.example.roadcrimedetector.processing.lanes

import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class LaneDetector {
    fun detect(frame: Mat): Pair<LineModel?, LineModel?> {
        val edges = LanePreprocessor.preprocess(frame)
        val segmentsMat = Mat()
        Imgproc.HoughLinesP(edges, segmentsMat, 1.0, Math.PI / 180, 30, 20.0, 150.0)
        val segments = mutableListOf<Point4>()
        for (index in 0 until segmentsMat.rows()) {
            val values = segmentsMat.get(index, 0)
            val segment = Point4(values[0].toFloat(), values[1].toFloat(), values[2].toFloat(), values[3].toFloat())
            val slope = if (segment.x2 - segment.x1 != 0f) (segment.y2 - segment.y1) / (segment.x2 - segment.x1) else 999f
            if (abs(slope) >= 0.3f) segments.add(segment)
        }
        edges.release(); segmentsMat.release()

        val midX = frame.width() / 2f
        val left = segments.filter { slope(it) < 0 && (it.x1 + it.x2) / 2f < midX }
        val right = segments.filter { slope(it) > 0 && (it.x1 + it.x2) / 2f > midX }
        return Pair(fit(left, frame.rows()), fit(right, frame.rows()))
    }

    private fun slope(segment: Point4) = (segment.y2 - segment.y1) / (segment.x2 - segment.x1 + 1e-6f)

    private fun fit(segments: List<Point4>, frameHeight: Int): LineModel? {
        if (segments.size < 2) return null
        val points = segments.flatMap { listOf(floatArrayOf(it.x1, it.y1), floatArrayOf(it.x2, it.y2)) }
        val n = points.size
        val sumX = points.sumOf { it[0].toDouble() }.toFloat()
        val sumY = points.sumOf { it[1].toDouble() }.toFloat()
        val sumXY = points.sumOf { (it[0] * it[1]).toDouble() }.toFloat()
        val sumXX = points.sumOf { (it[0] * it[0]).toDouble() }.toFloat()
        val denominator = n * sumXX - sumX * sumX
        if (abs(denominator) < 1e-3f) return null
        val m = (n * sumXY - sumX * sumY) / denominator
        val b = (sumY - m * sumX) / n
        val bottom = frameHeight.toFloat()
        val top = frameHeight * 0.60f
        return LineModel(m, b, LaneLineClassifier.classify(segments, bottom, top), segments, (bottom - b) / m, bottom, (top - b) / m, top)
    }
}
