package com.example.roadcrimedetector.processing.lanes

import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class LaneDetector {
    fun detect(frame: Mat): Pair<LineModel?, LineModel?> {
        val edges = LanePreprocessor.preprocess(frame)
        val segmentsMat = Mat()
        Imgproc.HoughLinesP(edges, segmentsMat, 1.0, Math.PI / 180, 30, 20.0, 150.0)
        val segments = mutableListOf<LineSegment>()
        for (index in 0 until segmentsMat.rows()) {
            val values = segmentsMat.get(index, 0)
            val first = LanePoint(values[0].toFloat(), values[1].toFloat())
            val second = LanePoint(values[2].toFloat(), values[3].toFloat())
            val segment = if (first.y <= second.y) LineSegment(first, second) else LineSegment(second, first)
            val slope = (segment.end.y - segment.start.y) / (segment.end.x - segment.start.x)
            if (abs(slope) >= 0.3f) segments.add(segment)
        }
        edges.release(); segmentsMat.release()

        val midX = frame.width() / 2f
        val left = segments.filter {
            it.start.x + it.end.x < midX * 2f && it.end.x <= it.start.x
        }
        val right = segments.filter {
            it.start.x + it.end.x > midX * 2f && it.end.x >= it.start.x
        }
        return Pair(createLine(left, frame.rows()), createLine(right, frame.rows()))
    }

    private fun createLine(segments: List<LineSegment>, frameHeight: Int): LineModel? {
        if (segments.isEmpty()) return null
        val top = frameHeight * 0.60f
        return LineModel(
            type = LaneLineClassifier.classify(segments, frameHeight.toFloat(), top),
            segments = segments
        )
    }
}
