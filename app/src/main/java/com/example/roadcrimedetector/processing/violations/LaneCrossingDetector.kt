package com.example.roadcrimedetector.processing.violations

import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.LaneCrossingViolation
import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.model.VehiclePosition
import com.example.roadcrimedetector.processing.lanes.LineModel
import com.example.roadcrimedetector.processing.lanes.LineType
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
        transverseLines: List<LineModel>,
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
                if (line == null || line.type != LineType.SOLID) {
                    track.sides.remove(lane)
                    track.pendingSides.remove(lane)
                    track.pendingFrames.remove(lane)
                    continue
                }
                val distance = signedDistance(track.x, track.y, line) ?: continue
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
                        violations += LaneCrossingViolation(
                            detection = detection,
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

    private fun signedDistance(x: Float, y: Float, line: LineModel): Float? {
        val distances = mutableListOf<Float>()
        for (segment in line.segments) {
            val dx = segment.end.x - segment.start.x
            val dy = segment.end.y - segment.start.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0f) continue

            val projection = ((x - segment.start.x) * dx + (y - segment.start.y) * dy) / lengthSquared
            if (projection !in -SEGMENT_MARGIN..(1f + SEGMENT_MARGIN)) continue

            val orientation = if (segment.start.y <= segment.end.y) 1f else -1f
            val distance = orientation * (dx * (y - segment.start.y) - dy * (x - segment.start.x)) /
                sqrt(lengthSquared)
            distances += distance
        }
        if (distances.isEmpty()) return null

        val edgeCenters = mutableListOf<Float>()
        val edgeCounts = mutableListOf<Int>()
        for (distance in distances.sorted()) {
            val lastIndex = edgeCenters.lastIndex
            if (lastIndex >= 0 && distance - edgeCenters[lastIndex] <= CONTOUR_EDGE_CLUSTER_TOLERANCE) {
                val count = edgeCounts[lastIndex] + 1
                edgeCenters[lastIndex] = (edgeCenters[lastIndex] * edgeCounts[lastIndex] + distance) / count
                edgeCounts[lastIndex] = count
            } else {
                edgeCenters += distance
                edgeCounts += 1
            }
        }

        val sortedCenters = edgeCenters.sorted()
        val middle = sortedCenters.size / 2
        return if (sortedCenters.size % 2 == 1) {
            sortedCenters[middle]
        } else {
            (sortedCenters[middle - 1] + sortedCenters[middle]) / 2f
        }
    }

    private fun classifySide(distance: Float, previousSide: Int?): Int = when {
        distance < -DISTANCE_HYSTERESIS -> -1
        distance > DISTANCE_HYSTERESIS -> 1
        else -> previousSide ?: 0
    }

    private fun laneDescription(lane: Lane): String = when (lane) {
        Lane.LEFT -> "lewej"
        Lane.RIGHT -> "prawej"
    }

    private companion object {
        const val DISTANCE_HYSTERESIS = 8f
        const val SEGMENT_MARGIN = 0.15f
        const val CONTOUR_EDGE_CLUSTER_TOLERANCE = 4f
        const val MAX_MATCH_COST = 2.0f
        const val MAX_MISSED_FRAMES = 3L
        const val CROSSING_CONFIRMATION_FRAMES = 2
    }
}
