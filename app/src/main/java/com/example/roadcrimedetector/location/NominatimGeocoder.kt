package com.example.roadcrimedetector.location

import com.example.roadcrimedetector.model.LocationAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class NominatimGeocoder(
    private val userAgent: String = "RoadCrimeDetector/1.0"
) {
    suspend fun findAddress(latitude: Double, longitude: Double): LocationAddress? =
        withContext(Dispatchers.IO) {
            val connection = (URL(buildUrl(latitude, longitude)).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", userAgent)

                if (connection.responseCode !in 200..299) return@withContext null

                val address = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .optJSONObject("address") ?: return@withContext null
                LocationAddress(
                    state = address.optString("state"),
                    road = address.optString("road"),
                    city = listOf("city", "town", "village", "municipality")
                        .firstNotNullOfOrNull { key -> address.optString(key).takeUnless(String::isEmpty) }
                )
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

    private fun buildUrl(latitude: Double, longitude: Double): String {
        val format = Locale.US
        return "https://nominatim.openstreetmap.org/reverse" +
            "?lat=${String.format(format, "%.7f", latitude)}" +
            "&lon=${String.format(format, "%.7f", longitude)}" +
            "&format=jsonv2&addressdetails=1&zoom=18"
    }
}