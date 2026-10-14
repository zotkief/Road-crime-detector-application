package com.example.roadcrimedetector.processing.violations

import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.processing.lanes.LineModel
import com.example.roadcrimedetector.processing.tracking.TrackedVehicle

class ViolationDetector(
    private val rules: List<ViolationRule> = listOf(
        LaneCrossingDetector(),
        OvertakingOnCrosswalkDetector()
    )
) {

    fun detect(
        leftLane: LineModel?,
        rightLane: LineModel?,
        transverseLines: List<LineModel>,
        trackedVehicles: Map<Int, TrackedVehicle>
    ): List<Violation> {
        val timestampMillis = System.currentTimeMillis()
        return rules.flatMap { rule ->
            rule.detect(
                leftLane,
                rightLane,
                transverseLines,
                timestampMillis,
                trackedVehicles
            )
        }
    }
}
