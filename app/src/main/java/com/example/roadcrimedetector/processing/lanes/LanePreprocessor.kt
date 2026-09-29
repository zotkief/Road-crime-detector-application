package com.example.roadcrimedetector.processing.lanes

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object LanePreprocessor {
    fun createWhiteMask(frame: Mat): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(frame, hsv, Imgproc.COLOR_BGR2HSV)
        val whiteMask = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 160.0), Scalar(180.0, 60.0, 255.0), whiteMask)
        hsv.release()

        val roiMask = Mat.zeros(whiteMask.size(), whiteMask.type())
        val height = whiteMask.rows()
        val width = whiteMask.cols()
        val points = MatOfPoint(
            Point(width * 0.03, height.toDouble()),
            Point(width * 0.37, height * 0.42),
            Point(width * 0.63, height * 0.42),
            Point(width * 0.97, height.toDouble())
        )
        Imgproc.fillPoly(roiMask, listOf(points), Scalar(255.0))
        val roiWhiteMask = Mat()
        Core.bitwise_and(whiteMask, roiMask, roiWhiteMask)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val cleanedMask = Mat()
        Imgproc.morphologyEx(roiWhiteMask, cleanedMask, Imgproc.MORPH_OPEN, kernel)

        whiteMask.release()
        roiMask.release()
        roiWhiteMask.release()
        points.release()
        kernel.release()
        return cleanedMask
    }
}
