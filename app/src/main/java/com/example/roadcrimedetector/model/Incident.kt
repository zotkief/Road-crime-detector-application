package com.example.roadcrimedetector.model

data class Incident(
    val id: String,
    val violation: Violation,
    val location: LocationSnapshot?,
    val recordedAtMillis: Long,
    val videoFiles: List<String> = emptyList()
)

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val provider: String?,
    val locationTimeMillis: Long
)