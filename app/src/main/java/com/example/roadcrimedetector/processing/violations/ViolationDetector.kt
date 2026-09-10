package com.example.roadcrimedetector.processing.violations

import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.processing.lanes.LineModel

class ViolationDetector {
    private val laneCrossingDetector = LaneCrossingDetector()

    fun detect(detections: List<Detection>, leftLane: LineModel?, rightLane: LineModel?): List<Violation> =
        laneCrossingDetector.detect(detections, leftLane, rightLane)
}
