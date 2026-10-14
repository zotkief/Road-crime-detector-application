package com.example.roadcrimedetector.processing.violations

import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.processing.lanes.LineModel
import com.example.roadcrimedetector.processing.tracking.TrackedVehicle

abstract class ViolationRule {
    abstract fun detect(
        leftLane: LineModel?,
        rightLane: LineModel?,
        transverseLines: List<LineModel>,
        timestampMillis: Long,
        trackedVehicles: Map<Int, TrackedVehicle>
    ): List<Violation>
}