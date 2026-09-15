package com.example.roadcrimedetector.processing.violations

import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.model.ViolationType
import com.example.roadcrimedetector.model.VehiclePosition
import com.example.roadcrimedetector.processing.lanes.LineModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class LaneCrossingDetector : ViolationRule() {
    private enum class Lane { LEFT, RIGHT }

    private data class TrackState(
        var x: Float,
        var y: Float,
        var width: Float,
        var height: Float,
        var lastSeenFrame: Long,
        val sides: MutableMap<Lane, Int> = mutableMapOf(),
        val pendingSides: MutableMap<Lane, Int> = mutableMapOf(),
        val pendingFrames: MutableMap<Lane, Int> = mutableMapOf(),
        val reported: MutableSet<Lane> = mutableSetOf()
    )

    private val tracks = mutableMapOf<Int, TrackState>()
    private var nextTrackId = 0
    private var frameNumber = 0L

    override fun detect(
        detections: List<Detection>,
        leftLane: LineModel?,
        rightLane: LineModel?,
        timestampMillis: Long
    ): List<Violation> {
        frameNumber++
        val matchedTracks = matchDetections(detections)
        val violations = mutableListOf<Violation>()

        for ((detectionIndex, trackId) in matchedTracks) {
            val detection = detections[detectionIndex]
            val track = tracks.getValue(trackId)
            val lines = listOf(Lane.LEFT to leftLane, Lane.RIGHT to rightLane)
            for ((lane, line) in lines) {
                if (line == null || !isWithinSegment(track.x, track.y, line)) continue
                val distance = signedDistance(track.x, track.y, line)
                val side = classifySide(distance, track.sides[lane])
                val previousSide = track.sides[lane]
                if (side != 0 && previousSide != null && previousSide != 0 && side != previousSide) {
                    val pendingFrames = if (track.pendingSides[lane] == side) {
                        (track.pendingFrames[lane] ?: 0) + 1
                    } else {
                        1
                    }
                    track.pendingSides[lane] = side
                    track.pendingFrames[lane] = pendingFrames
                    if (pendingFrames >= CROSSING_CONFIRMATION_FRAMES && lane !in track.reported) {
                        val direction = if (previousSide < side) "A -> B" else "B -> A"
                        violations += Violation(
                            detection = detection,
                            type = ViolationType.LANE_VIOLATION,
                            description = "Przekroczenie ${laneDescription(lane)} linii ($direction)",
                            timestampMillis = timestampMillis,
                            position = VehiclePosition(
                                x1 = detection.box[0],
                                y1 = detection.box[1],
                                x2 = detection.box[2],
                                y2 = detection.box[3]
                            )
                        )
                        track.reported += lane
                        track.sides[lane] = side
                        track.pendingSides.remove(lane)
                        track.pendingFrames.remove(lane)
                    }
                } else {
                    track.pendingSides.remove(lane)
                    track.pendingFrames.remove(lane)
                    if (side != 0) track.sides[lane] = side
                }
            }
        }

        tracks.values.removeAll { frameNumber - it.lastSeenFrame > MAX_MISSED_FRAMES }
        return violations
    }

    private fun matchDetections(detections: List<Detection>): Map<Int, Int> {
        val candidates = mutableListOf<Triple<Float, Int, Int>>()
        for ((trackId, track) in tracks) {
            for ((index, detection) in detections.withIndex()) {
                val box = detection.box
                val width = (box[2] - box[0]).coerceAtLeast(1f)
                val height = (box[3] - box[1]).coerceAtLeast(1f)
                val x = (box[0] + box[2]) / 2f
                val y = box[3]
                val distance = sqrt((x - track.x) * (x - track.x) + (y - track.y) * (y - track.y))
                val sizeDifference = abs(width - track.width) / max(width, track.width) +
                    abs(height - track.height) / max(height, track.height)
                val cost = distance / max(track.width, track.height) + sizeDifference
                if (cost <= MAX_MATCH_COST) candidates += Triple(cost, trackId, index)
            }
        }

        val matches = mutableMapOf<Int, Int>()
        val usedTracks = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()
        for ((_, trackId, detectionIndex) in candidates.sortedBy { it.first }) {
            if (trackId in usedTracks || detectionIndex in usedDetections) continue
            val detection = detections[detectionIndex]
            val box = detection.box
            val track = tracks.getValue(trackId)
            track.x = (box[0] + box[2]) / 2f
            track.y = box[3]
            track.width = (box[2] - box[0]).coerceAtLeast(1f)
            track.height = (box[3] - box[1]).coerceAtLeast(1f)
            track.lastSeenFrame = frameNumber
            matches[detectionIndex] = trackId
            usedTracks += trackId
            usedDetections += detectionIndex
        }

        for ((index, detection) in detections.withIndex()) {
            if (index in usedDetections) continue
            val box = detection.box
            val id = nextTrackId++
            tracks[id] = TrackState(
                x = (box[0] + box[2]) / 2f,
                y = box[3],
                width = (box[2] - box[0]).coerceAtLeast(1f),
                height = (box[3] - box[1]).coerceAtLeast(1f),
                lastSeenFrame = frameNumber
            )
            matches[index] = id
        }
        return matches
    }

    private fun signedDistance(x: Float, y: Float, line: LineModel): Float = y - (line.m * x + line.b)

    private fun classifySide(distance: Float, previousSide: Int?): Int = when {
        distance < -DISTANCE_HYSTERESIS -> -1
        distance > DISTANCE_HYSTERESIS -> 1
        else -> previousSide ?: 0
    }

    private fun isWithinSegment(x: Float, y: Float, line: LineModel): Boolean {
        val dx = line.x2 - line.x1
        val dy = line.y2 - line.y1
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return false
        val projection = ((x - line.x1) * dx + (y - line.y1) * dy) / lengthSquared
        return projection in -SEGMENT_MARGIN..(1f + SEGMENT_MARGIN)
    }

    private fun laneDescription(lane: Lane): String = when (lane) {
        Lane.LEFT -> "lewej"
        Lane.RIGHT -> "prawej"
    }

    private companion object {
        const val DISTANCE_HYSTERESIS = 8f
        const val SEGMENT_MARGIN = 0.15f
        const val MAX_MATCH_COST = 2.0f
        const val MAX_MISSED_FRAMES = 3L
        const val CROSSING_CONFIRMATION_FRAMES = 2
    }
}
