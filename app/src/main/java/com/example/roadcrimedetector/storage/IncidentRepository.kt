package com.example.roadcrimedetector.storage

import android.content.Context
import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.Incident
import com.example.roadcrimedetector.model.LocationSnapshot
import com.example.roadcrimedetector.model.VehiclePosition
import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.model.ViolationType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class IncidentRepository(context: Context) {
    private val root = File(context.filesDir, "incidents").apply { mkdirs() }
    private val metadataFile = File(root, "incidents.json")

    @Synchronized
    fun save(violation: Violation, location: LocationSnapshot?): Incident {
        val incident = Incident(
            id = java.util.UUID.randomUUID().toString(),
            violation = violation,
            location = location,
            recordedAtMillis = System.currentTimeMillis()
        )
        val incidents = readAll().toMutableList()
        incidents += incident
        writeAll(incidents)
        return incident
    }

    @Synchronized
    fun attachVideos(incidentId: String, videoFiles: List<File>) {
        val incidents = readAll().map { incident ->
            if (incident.id == incidentId) {
                incident.copy(videoFiles = videoFiles.map { it.absolutePath })
            } else {
                incident
            }
        }
        writeAll(incidents)
    }

    @Synchronized
    fun readAll(): List<Incident> {
        if (!metadataFile.exists()) return emptyList()
        val json = JSONArray(metadataFile.readText())
        return List(json.length()) { index -> fromJson(json.getJSONObject(index)) }
    }

    private fun writeAll(incidents: List<Incident>) {
        val json = JSONArray()
        incidents.forEach { json.put(toJson(it)) }
        metadataFile.writeText(json.toString())
    }

    private fun toJson(incident: Incident) = JSONObject().apply {
        put("id", incident.id)
        put("recordedAtMillis", incident.recordedAtMillis)
        put("violation", JSONObject().apply {
            put("label", incident.violation.detection.label)
            put("confidence", incident.violation.detection.confidence)
            put("classId", incident.violation.detection.classId)
            put("box", JSONArray(incident.violation.detection.box.toList()))
            put("type", incident.violation.type.name)
            put("description", incident.violation.description)
            put("timestampMillis", incident.violation.timestampMillis)
            put("position", JSONObject().apply {
                put("x1", incident.violation.position.x1)
                put("y1", incident.violation.position.y1)
                put("x2", incident.violation.position.x2)
                put("y2", incident.violation.position.y2)
            })
        })
        incident.location?.let { location ->
            put("location", JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracyMeters", location.accuracyMeters)
                put("altitudeMeters", location.altitudeMeters)
                put("provider", location.provider)
                put("locationTimeMillis", location.locationTimeMillis)
            })
        }
        put("videoFiles", JSONArray(incident.videoFiles))
    }

    private fun fromJson(json: JSONObject): Incident {
        val violationJson = json.getJSONObject("violation")
        val positionJson = violationJson.getJSONObject("position")
        val boxJson = violationJson.getJSONArray("box")
        val box = FloatArray(boxJson.length()) { boxJson.getDouble(it).toFloat() }
        val detection = Detection(
            label = violationJson.getString("label"),
            confidence = violationJson.getDouble("confidence"),
            box = box,
            classId = violationJson.getInt("classId")
        )
        val violation = Violation(
            detection = detection,
            type = ViolationType.valueOf(violationJson.getString("type")),
            description = violationJson.getString("description"),
            timestampMillis = violationJson.getLong("timestampMillis"),
            position = VehiclePosition(
                positionJson.getDouble("x1").toFloat(),
                positionJson.getDouble("y1").toFloat(),
                positionJson.getDouble("x2").toFloat(),
                positionJson.getDouble("y2").toFloat()
            )
        )
        val locationJson = json.optJSONObject("location")
        val location = locationJson?.let {
            LocationSnapshot(
                latitude = it.getDouble("latitude"),
                longitude = it.getDouble("longitude"),
                accuracyMeters = it.optDouble("accuracyMeters").takeUnless { value -> value.isNaN() }?.toFloat(),
                altitudeMeters = it.optDouble("altitudeMeters").takeUnless { value -> value.isNaN() },
                provider = it.optString("provider").takeUnless(String::isEmpty),
                locationTimeMillis = it.getLong("locationTimeMillis")
            )
        }
        val videos = json.optJSONArray("videoFiles")?.let { files ->
            List(files.length()) { files.getString(it) }
        } ?: emptyList()
        return Incident(
            id = json.getString("id"),
            violation = violation,
            location = location,
            recordedAtMillis = json.optLong("recordedAtMillis", violation.timestampMillis),
            videoFiles = videos
        )
    }
}