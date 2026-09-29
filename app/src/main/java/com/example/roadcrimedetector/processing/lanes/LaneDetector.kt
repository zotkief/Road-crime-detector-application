package com.example.roadcrimedetector.processing.lanes

import org.opencv.core.Mat
import org.opencv.core.Core
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.sqrt

class LaneDetector {
    fun detect(frame: Mat): Pair<LineModel?, LineModel?> {
        val mask = LanePreprocessor.createWhiteMask(frame)
        val edges = Mat()
        Imgproc.Canny(mask, edges, 40.0, 120.0)
        val segmentsMat = Mat()
        Imgproc.HoughLinesP(edges, segmentsMat, 1.0, Math.PI / 180, 10, 8.0, 4.0)
        val segments = mutableListOf<LineSegment>()
        for (index in 0 until segmentsMat.rows()) {
            val values = segmentsMat.get(index, 0)
            val first = LanePoint(values[0].toFloat(), values[1].toFloat())
            val second = LanePoint(values[2].toFloat(), values[3].toFloat())
            val segment = if (first.y <= second.y) LineSegment(first, second) else LineSegment(second, first)
            val deltaX = segment.end.x - segment.start.x
            val deltaY = segment.end.y - segment.start.y
            val length = sqrt(deltaX * deltaX + deltaY * deltaY)
            val angle = Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble()))
            val centerX = (segment.start.x + segment.end.x) / 2f
            val centerY = (segment.start.y + segment.end.y) / 2f
            if (length >= 8f && angle in 18.0..162.0 &&
                centerY >= frame.rows() * 0.42f &&
                centerX in (frame.cols() * 0.03f)..(frame.cols() * 0.97f)
            ) {
                segments.add(segment)
            }
        }
        edges.release()
        segmentsMat.release()

        val verifiedSegments = filterSegmentsUsingMask(segments, mask)
        mask.release()

        val midX = frame.width() / 2f
        val left = verifiedSegments.filter { (it.start.x + it.end.x) / 2f < midX }
        val right = verifiedSegments.filter { (it.start.x + it.end.x) / 2f >= midX }
        return Pair(createLine(left), createLine(right))
    }

    private fun filterSegmentsUsingMask(segments: List<LineSegment>, mask: Mat): List<LineSegment> {
        val lineMask = Mat.zeros(mask.size(), mask.type())
        val overlap = Mat()
        val accepted = segments.filter { segment ->
            lineMask.setTo(Scalar(0.0))
            Imgproc.line(
                lineMask,
                Point(segment.start.x.toDouble(), segment.start.y.toDouble()),
                Point(segment.end.x.toDouble(), segment.end.y.toDouble()),
                Scalar(255.0),
                3
            )
            Core.bitwise_and(lineMask, mask, overlap)
            val linePixels = Core.countNonZero(lineMask)
            linePixels > 0 && Core.countNonZero(overlap).toDouble() / linePixels >= 0.30
        }
        lineMask.release()
        overlap.release()
        return accepted
    }

    private fun createLine(segments: List<LineSegment>): LineModel? {
        if (segments.isEmpty()) return null
        return LineModel(
            type = LineType.UNKNOWN,
            segments = segments
        )
    }
}
