package com.example.roadcrimedetector.model

import com.example.roadcrimedetector.location.NominatimGeocoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

abstract class Violation(
    val detection: Detection,
    val description: String,
    val timestampMillis: Long,
    val position: VehiclePosition
) {
    val eventTimestampPolish: String
        get() = Instant.ofEpochMilli(timestampMillis)
            .atZone(ZoneId.of("Europe/Warsaw"))
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))

    abstract fun violationType(): ViolationType

    suspend fun generateEmail(location: LocationSnapshot?): String {
        val address = location?.let {
            NominatimGeocoder().findAddress(it.latitude, it.longitude)
        }
        val roadName = address?.road?.takeUnless(String::isBlank) ?: "nieznana"
        val cityName = address?.city ?: "nieznane"

        return """
            TESTOWA TREŚĆ EMAILA

            Typ zdarzenia: ${violationType()}
            Opis: $description
            Czas zdarzenia: $eventTimestampPolish
            Droga: $roadName
            Miasto: $cityName
        """.trimIndent()
    }
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
