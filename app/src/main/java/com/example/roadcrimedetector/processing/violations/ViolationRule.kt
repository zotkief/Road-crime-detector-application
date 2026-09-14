package com.example.roadcrimedetector.processing.violations

import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.processing.lanes.LineModel

abstract class ViolationRule {
    abstract fun detect(
        detections: List<Detection>,
        leftLane: LineModel?,
        rightLane: LineModel?,
        timestampMillis: Long
    ): List<Violation>
}