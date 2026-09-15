package com.example.roadcrimedetector.model

data class Violation(
    val detection: Detection,
    val type: ViolationType,
    val description: String,
    val timestampMillis: Long,
    val position: VehiclePosition
)

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
