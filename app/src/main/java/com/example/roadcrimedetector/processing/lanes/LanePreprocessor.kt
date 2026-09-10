package com.example.roadcrimedetector.processing.lanes

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object LanePreprocessor {
    fun preprocess(frame: Mat): Mat {
        val hls = Mat()
        Imgproc.cvtColor(frame, hls, Imgproc.COLOR_BGR2HLS)
        val whiteMask = Mat()
        Core.inRange(hls, Scalar(0.0, 200.0, 0.0), Scalar(255.0, 255.0, 255.0), whiteMask)
        val yellowMask = Mat()
        Core.inRange(hls, Scalar(15.0, 30.0, 115.0), Scalar(35.0, 204.0, 255.0), yellowMask)
        val colorMask = Mat()
        Core.bitwise_or(whiteMask, yellowMask, colorMask)
        val gray = Mat()
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
        val masked = Mat()
        Core.bitwise_and(gray, gray, masked, colorMask)
        val blurred = Mat()
        Imgproc.GaussianBlur(masked, blurred, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 50.0, 150.0)
        hls.release(); whiteMask.release(); yellowMask.release(); colorMask.release()
        gray.release(); masked.release(); blurred.release()

        val mask = Mat.zeros(edges.size(), edges.type())
        val height = edges.rows()
        val width = edges.cols()
        val points = MatOfPoint(
            Point(width * 0.05, height.toDouble()),
            Point(width * 0.45, height * 0.60),
            Point(width * 0.55, height * 0.60),
            Point(width * 0.95, height.toDouble())
        )
        Imgproc.fillPoly(mask, listOf(points), Scalar(255.0))
        val result = Mat()
        Core.bitwise_and(edges, mask, result)
        edges.release(); mask.release(); points.release()
        return result
    }
}
