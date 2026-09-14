package com.example.roadcrimedetector.processing.violations

import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.processing.lanes.LineModel
import android.os.SystemClock

class ViolationDetector(
    private val rules: List<ViolationRule> = listOf(
        LaneCrossingDetector()
    ),
    private val startedAtMillis: Long = SystemClock.elapsedRealtime()
) {

    fun detect(
        detections: List<Detection>,
        leftLane: LineModel?,
        rightLane: LineModel?
    ): List<Violation> =
        rules.flatMap { rule ->
            rule.detect(
                detections,
                leftLane,
                rightLane,
                SystemClock.elapsedRealtime() - startedAtMillis
            )
        }
}
