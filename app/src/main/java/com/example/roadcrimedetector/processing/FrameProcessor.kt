package com.example.roadcrimedetector.processing

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.FrameResult
import com.example.roadcrimedetector.processing.detection.YoloDetector
import com.example.roadcrimedetector.processing.lanes.LaneDetector
import com.example.roadcrimedetector.processing.tracking.Bbox
import com.example.roadcrimedetector.processing.tracking.VehicleTracker
import com.example.roadcrimedetector.processing.violations.ViolationDetector
import com.example.roadcrimedetector.rendering.FrameRenderer

const val YOLO_INTERVAL = 200L

class FrameProcessor(context: Context) : AutoCloseable {
    private val yoloDetector = YoloDetector(context)
    private val vehicleTracker = VehicleTracker()
    private val laneDetector = LaneDetector()
    private val violationDetector = ViolationDetector()
    private val frameRenderer = FrameRenderer()
    private var lastDetectTime = 0L
    private var lastDetections = emptyList<Detection>()

    fun process(bitmap: Bitmap): FrameResult {
        val mat = Mat()
        try {
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)

            val now = System.currentTimeMillis()
            if (now - lastDetectTime > YOLO_INTERVAL) {
                val yoloDetections = yoloDetector.detect(mat)
                lastDetections = yoloDetections.map {
                    Detection("vehicle", it.confidence.toDouble(), floatArrayOf(it.x1, it.y1, it.x2, it.y2), it.classId)
                }
                vehicleTracker.update(yoloDetections.map { Bbox(it.x1, it.y1, it.x2, it.y2) }, now)
                lastDetectTime = now
            }

            val laneResult = laneDetector.detect(mat)
            val violations = violationDetector.detect(
                lastDetections,
                laneResult.leftLane,
                laneResult.rightLane,
                laneResult.transverseLines
            )
            frameRenderer.render(mat, vehicleTracker.current(), *laneResult.allLines.toTypedArray())

            val output = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, output)
            return FrameResult(output, lastDetections, violations)
        } finally {
            mat.release()
        }
    }

    override fun close() {
        yoloDetector.close()
    }
}
