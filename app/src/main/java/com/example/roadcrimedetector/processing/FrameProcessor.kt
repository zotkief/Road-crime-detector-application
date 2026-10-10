package com.example.roadcrimedetector.processing

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.FrameResult
import com.example.roadcrimedetector.processing.detection.YoloDetector
import com.example.roadcrimedetector.processing.lanes.LaneClassification
import com.example.roadcrimedetector.processing.lanes.LaneDetector
import com.example.roadcrimedetector.processing.lanes.LaneLineClassifier
import com.example.roadcrimedetector.processing.tracking.Bbox
import com.example.roadcrimedetector.processing.tracking.VehicleTracker
import com.example.roadcrimedetector.processing.violations.ViolationDetector
import com.example.roadcrimedetector.rendering.FrameRenderer

const val YOLO_INTERVAL = 200L

class FrameProcessor(context: Context) : AutoCloseable {
    private val yoloDetector = YoloDetector(context)
    private val vehicleTracker = VehicleTracker()
    private val laneDetector = LaneDetector(context)
    private val laneLineClassifier = LaneLineClassifier(frameIsBgr = true)
    private val violationDetector = ViolationDetector()
    private val frameRenderer = FrameRenderer()
    private var lastDetectTime = 0L
    private var lastDetections = emptyList<Detection>()
    private var lastLaneClassification: LaneClassification? = null

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
                val laneMask = laneDetector.detect(mat)
                lastLaneClassification = laneLineClassifier.classify(laneMask, mat)
                lastDetectTime = now
            }

            val laneClassification = lastLaneClassification
            val violations = violationDetector.detect(
                lastDetections,
                laneClassification?.leftLane,
                laneClassification?.rightLane,
                emptyList()
            )
            frameRenderer.render(
                mat,
                vehicleTracker.current(),
                laneClassification?.displayMask
            )

            val output = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, output)
            return FrameResult(output, lastDetections, violations)
        } finally {
            mat.release()
        }
    }

    override fun close() {
        yoloDetector.close()
        laneDetector.close()
    }
}
