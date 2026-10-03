package com.example.roadcrimedetector.processing.lanes

import org.opencv.core.Mat
import org.opencv.core.Core
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

class LaneDetector {
    fun detect(frame: Mat): LaneDetectionResult {
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
            val centerX = (segment.start.x + segment.end.x) / 2f
            val centerY = (segment.start.y + segment.end.y) / 2f
            if (length >= 8f &&
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

        val yTop = frame.rows() * 0.42f
        val yBottom = frame.rows().toFloat()
        val transverse = verifiedSegments.filter { segment ->
            val dx = kotlin.math.abs(segment.end.x - segment.start.x)
            val dy = kotlin.math.abs(segment.end.y - segment.start.y)
            dx > 0f && dy <= dx * 0.35f
        }
        val remaining = verifiedSegments - transverse.toSet()
        val laneShaped = remaining.filter { segment ->
            val dx = kotlin.math.abs(segment.end.x - segment.start.x)
            val dy = kotlin.math.abs(segment.end.y - segment.start.y)
            dy >= 1f && dx / dy in 0.08f..2.5f
        }
        val midX = frame.width() / 2f
        val leftSegments = laneShaped.filter { (it.start.x + it.end.x) / 2f < midX }
        val rightSegments = laneShaped.filter { (it.start.x + it.end.x) / 2f >= midX }
        val leftType = LaneLineClassifier.classify(leftSegments, yBottom, yTop)
        val rightType = LaneLineClassifier.classify(rightSegments, yBottom, yTop)
        val left = createLaneLine(leftSegments, leftType)
        val right = createLaneLine(rightSegments, rightType)
        val otherSegments = (remaining - laneShaped.toSet()) +
            (if (leftType == LineType.OTHER) leftSegments else emptyList()) +
            (if (rightType == LineType.OTHER) rightSegments else emptyList())
        val transverseType = LaneLineClassifier.classify(transverse, yBottom, yTop)
        val otherType = LaneLineClassifier.classify(otherSegments, yBottom, yTop)

        return LaneDetectionResult(
            leftLane = left,
            rightLane = right,
            transverseLines = if (transverse.isEmpty()) emptyList() else listOf(LineModel(transverseType, transverse)),
            otherLines = if (otherSegments.isEmpty()) emptyList() else listOf(LineModel(otherType, otherSegments))
        )
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

    private fun createLaneLine(segments: List<LineSegment>, type: LineType): LineModel? =
        if (segments.isEmpty() || type == LineType.OTHER || type == LineType.TRANSVERSE) null
        else LineModel(type, segments)
}
