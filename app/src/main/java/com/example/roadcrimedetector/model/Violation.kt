package com.example.roadcrimedetector.model

data class Violation(
    val detection: Detection,
    val type: ViolationType,
    val description: String,
    val timestampMillis: Long,
    val position: VehiclePosition
)

data class VehiclePosition(
    val x: Float,
    val y: Float
)

enum class ViolationType {
    LANE_VIOLATION,
    OTHER
}
