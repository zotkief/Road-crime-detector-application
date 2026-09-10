package com.example.roadcrimedetector.model

data class Violation(
    val detection: Detection,
    val type: ViolationType,
    val description: String
)

enum class ViolationType {
    LANE_VIOLATION,
    OTHER
}
