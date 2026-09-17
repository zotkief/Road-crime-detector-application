package com.example.roadcrimedetector.storage

import android.content.Context
import android.graphics.Bitmap
import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.Incident
import com.example.roadcrimedetector.model.LaneCrossingViolation
import com.example.roadcrimedetector.model.LocationSnapshot
import com.example.roadcrimedetector.model.StoredOtherViolation
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
    fun save(
        violation: Violation,
        location: LocationSnapshot?,
        snapshot: Bitmap? = null
    ): Incident {
        val id = java.util.UUID.randomUUID().toString()
        val snapshotFile = snapshot?.let {
            File(root, "$id/snapshot.png").apply {
                parentFile?.mkdirs()
                outputStream().use { output -> it.compress(Bitmap.CompressFormat.PNG, 100, output) }
            }
        }
        val incident = Incident(
            id = id,
            violation = violation,
            location = location,
            recordedAtMillis = System.currentTimeMillis(),
            snapshotFile = snapshotFile?.absolutePath
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
    fun updateDetails(incidentId: String, emailContent: String, roadName: String) {
        val incidents = readAll().map { incident ->
            if (incident.id == incidentId) {
                incident.copy(emailContent = emailContent, roadName = roadName)
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
        incident.snapshotFile?.let { put("snapshotFile", it) }
        put("violation", JSONObject().apply {
            put("label", incident.violation.detection.label)
            put("confidence", incident.violation.detection.confidence)
            put("classId", incident.violation.detection.classId)
            put("box", JSONArray(incident.violation.detection.box.toList()))
            put("type", incident.violation.violationType().name)
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
        put("emailContent", incident.emailContent)
        put("roadName", incident.roadName)
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
        val description = violationJson.getString("description")
        val timestampMillis = violationJson.getLong("timestampMillis")
        val position = VehiclePosition(
            positionJson.getDouble("x1").toFloat(),
            positionJson.getDouble("y1").toFloat(),
            positionJson.getDouble("x2").toFloat(),
            positionJson.getDouble("y2").toFloat()
        )
        val violationType = ViolationType.valueOf(violationJson.getString("type"))
        val violation = when (violationType) {
            ViolationType.LANE_VIOLATION -> LaneCrossingViolation(
                detection,
                description,
                timestampMillis,
                position
            )
            ViolationType.OTHER -> StoredOtherViolation(
                detection,
                description,
                timestampMillis,
                position
            )
        }
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
            snapshotFile = json.optString("snapshotFile").takeUnless(String::isEmpty),
            videoFiles = videos,
            emailContent = json.optString("emailContent"),
            roadName = json.optString("roadName")
        )
    }
}