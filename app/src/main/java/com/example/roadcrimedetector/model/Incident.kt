package com.example.roadcrimedetector.model

data class Incident(
    val id: String,
    val violation: Violation,
    val location: LocationSnapshot?,
    val recordedAtMillis: Long,
    val snapshotFile: String? = null,
    val videoFiles: List<String> = emptyList(),
    val emailContent: String = "",
    val roadName: String = ""
)

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val provider: String?,
    val locationTimeMillis: Long
)