package com.example.roadcrimedetector.rendering

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.imgproc.Imgproc
import com.example.roadcrimedetector.processing.lanes.ClassifiedLaneMask
import com.example.roadcrimedetector.processing.tracking.TrackedVehicle

class FrameRenderer {
    fun render(
        mat: Mat,
        trackers: Map<Int, TrackedVehicle>,
        laneMask: ClassifiedLaneMask?
    ) {
        laneMask?.let { drawLaneMask(mat, it) }

        for ((id, tracker) in trackers) {
            val box = tracker.bbox
            Imgproc.rectangle(mat, Point(box.x1.toDouble(), box.y1.toDouble()), Point(box.x2.toDouble(), box.y2.toDouble()), Scalar(0.0, 255.0, 0.0), 2)
            Imgproc.putText(mat, "ID $id", Point(box.x1.toDouble(), (box.y1 - 10).toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, Scalar(255.0, 255.0, 255.0), 2)
        }
    }

    private fun drawLaneMask(mat: Mat, mask: ClassifiedLaneMask) {
        val sourceLabels = Mat(mask.height, mask.width, CvType.CV_8UC1)
        val resizedLabels = Mat()
        val linePixels = Mat()
        try {
            sourceLabels.put(0, 0, mask.labels)
            Imgproc.resize(
                sourceLabels,
                resizedLabels,
                mat.size(),
                0.0,
                0.0,
                Imgproc.INTER_NEAREST
            )
            for ((label, color) in listOf(
                ClassifiedLaneMask.CONTINUOUS to Scalar(255.0, 0.0, 0.0), //czerwony
                ClassifiedLaneMask.DASHED to Scalar(0.0, 255.0, 0.0), //zielony
                ClassifiedLaneMask.UNKNOWN to Scalar(0.0, 0.0, 255.0), //niebieski
                ClassifiedLaneMask.CROSSING to Scalar(255.0, 0.0, 255.0), //różowy
                ClassifiedLaneMask.LEFT_CONTINOUS to Scalar(0.0, 255.0, 255.0), //jasnoniebieski
                ClassifiedLaneMask.RIGHT_CONTINOUS to Scalar(255.0, 255.0, 255.0) //biały
            )) {
                Core.compare(resizedLabels, Scalar(label.toDouble()), linePixels, Core.CMP_EQ)
                mat.setTo(color, linePixels)
            }
        } finally {
            sourceLabels.release()
            resizedLabels.release()
            linePixels.release()
        }
    }
}
