package com.example.roadcrimedetector.rendering

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import com.example.roadcrimedetector.processing.lanes.LineModel
import com.example.roadcrimedetector.processing.lanes.LineType
import com.example.roadcrimedetector.processing.tracking.TrackedVehicle

class FrameRenderer {
    fun render(mat: Mat, trackers: Map<Int, TrackedVehicle>, vararg lines: LineModel?) {
        for ((id, tracker) in trackers) {
            val box = tracker.bbox
            Imgproc.rectangle(mat, Point(box.x1.toDouble(), box.y1.toDouble()), Point(box.x2.toDouble(), box.y2.toDouble()), Scalar(0.0, 255.0, 0.0), 2)
            Imgproc.putText(mat, "ID $id", Point(box.x1.toDouble(), (box.y1 - 10).toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, Scalar(255.0, 255.0, 255.0), 2)
        }
        for (line in lines) {
            line ?: continue
            val color = when (line.type) {
                LineType.SOLID -> Scalar(0.0, 0.0, 255.0)
                LineType.DASHED -> Scalar(255.0, 0.0, 0.0)
                LineType.UNKNOWN -> Scalar(128.0, 128.0, 128.0)
            }
            for (segment in line.segments) {
                Imgproc.line(
                    mat,
                    Point(segment.start.x.toDouble(), segment.start.y.toDouble()),
                    Point(segment.end.x.toDouble(), segment.end.y.toDouble()),
                    color,
                    3
                )
            }
        }
    }
}
