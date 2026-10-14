package com.example.roadcrimedetector.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.roadcrimedetector.location.NominatimGeocoder
import com.example.roadcrimedetector.utils.identifyCar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    suspend fun generateEmail(
        location: LocationSnapshot?,
        snapshotFile: String?,
        personalData: PersonalData? = null
    ): String {
        val vehicleDetails = snapshotFile?.let { identifyVehicle(it) }
        val address = location?.let {
            NominatimGeocoder().findAddress(it.latitude, it.longitude)
        }
        val roadName = address?.road?.takeUnless(String::isBlank) ?: "Nieznana"
        val cityName = address?.city?.takeUnless(String::isBlank) ?: "Nieznane"
        val regionName = address?.state?.takeUnless(String::isBlank) ?: "Nieznane"
        val coordinates = location?.let {
            "Szerokość: ${it.latitude}, długość: ${it.longitude}"
        } ?: "Brak danych GPS"
        val (make, registration) = vehicleDetails
            ?: ("Nieustalona (brak dostępnej klatki)" to "Nieustalony")
        val eventType = when (violationType()) {
            ViolationType.LANE_VIOLATION -> "Przekroczenie linii"
            ViolationType.OVERTAKING_ON_CROSSWALK -> "Wyprzedzanie na przejściu dla pieszych"
            ViolationType.OTHER -> "Inne zdarzenie drogowe"
        }
        val personalDataSection = personalData?.let { data ->
            listOfNotNull(
                (data.firstName + " " + data.lastName).trim().takeIf(String::isNotEmpty),
                data.homeAddress.takeIf(String::isNotBlank)
            ).takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "\n", prefix = "\n\nDANE ZGŁASZAJĄCEGO\n")
        }.orEmpty()

        return """
            Szanowni Państwo,

            Zgłaszam zdarzenie drogowe zarejestrowane przez aplikację Road Crime Detector.

            DANE ZDARZENIA
            Rodzaj zdarzenia: $eventType
            Opis: $description
            Data i godzina: $eventTimestampPolish
            Lokalizacja: $roadName, $cityName, woj. $regionName
            $coordinates

            DANE POJAZDU
            Marka: $make
            Numer rejestracyjny: $registration
            Pewność wykrycia pojazdu: ${"%.1f".format(java.util.Locale.US, detection.confidence * 100)}%

            Nagranie dokumentujące zdarzenie zostało dołączone do wiadomości.

            Z poważaniem
        """.trimIndent() + personalDataSection
    }

    private suspend fun identifyVehicle(snapshotFile: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val snapshot = BitmapFactory.decodeFile(snapshotFile)
                ?: return@withContext null
            try {
                val vehicleImage = snapshot.crop(position) ?: return@withContext null
                try {
                    identifyCar(vehicleImage)
                } finally {
                    if (vehicleImage !== snapshot) vehicleImage.recycle()
                }
            } finally {
                snapshot.recycle()
            }
        }

    private fun Bitmap.crop(position: VehiclePosition): Bitmap? {
        val coordinates = listOf(position.x1, position.y1, position.x2, position.y2)
        if (coordinates.any { !it.isFinite() }) return null

        val left = floor(minOf(position.x1, position.x2)).toInt().coerceIn(0, width)
        val top = floor(minOf(position.y1, position.y2)).toInt().coerceIn(0, height)
        val right = ceil(maxOf(position.x1, position.x2)).toInt().coerceIn(0, width)
        val bottom = ceil(maxOf(position.y1, position.y2)).toInt().coerceIn(0, height)
        if (right <= left || bottom <= top) return null

        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
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

class OvertakingOnCrosswalkViolation(
    detection: Detection,
    description: String,
    timestampMillis: Long,
    position: VehiclePosition
) : Violation(detection, description, timestampMillis, position) {
    override fun violationType(): ViolationType = ViolationType.OVERTAKING_ON_CROSSWALK
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
    OVERTAKING_ON_CROSSWALK,
    OTHER
}
