package com.example.roadcrimedetector.model

abstract class Violation(
    val detection: Detection,
    val description: String,
    val timestampMillis: Long,
    val position: VehiclePosition
) {
    abstract fun violationType(): ViolationType

    fun generateEmail(roadName: String): String = "email_$roadName"
}

class LaneCrossingViolation(
    detection: Detection,
    description: String,
    timestampMillis: Long,
    position: VehiclePosition
) : Violation(detection, description, timestampMillis, position) {
    override fun violationType(): ViolationType = ViolationType.LANE_VIOLATION
}

internal class StoredOtherViolation(
    detection: Detection,
    description: String,
    timestampMillis: Long,
    position: VehiclePosition
) : Violation(detection, description, timestampMillis, position) {
    override fun violationType(): ViolationType = ViolationType.OTHER
}

data class VehiclePosition(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

enum class ViolationType {
    LANE_VIOLATION,
    OTHER
}
