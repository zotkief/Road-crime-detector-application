package com.example.roadcrimedetector.processing.violations

import com.example.roadcrimedetector.model.Detection
import com.example.roadcrimedetector.model.OvertakingOnCrosswalkViolation
import com.example.roadcrimedetector.model.VehiclePosition
import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.processing.lanes.LineModel
import com.example.roadcrimedetector.processing.lanes.LineType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class OvertakingOnCrosswalkDetector : ViolationRule() {
    private data class VehicleSample(
        val detection: Detection,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val frame: Long
    )

    private data class Track(
        val id: Int,
        val history: ArrayDeque<VehicleSample> = ArrayDeque(),
        var lastSeenFrame: Long = 0L
    )

    private data class Crosswalk(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val stripeCount: Int
    ) {
        val centerX: Float get() = (minX + maxX) / 2f
        val centerY: Float get() = (minY + maxY) / 2f
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY
    }

    private data class PairKey(val firstId: Int, val secondId: Int)

    private data class PairState(
        val orderHistory: ArrayDeque<Int> = ArrayDeque(),
        var reported: Boolean = false
    )

    private val tracks = mutableMapOf<Int, Track>()
    private val pairs = mutableMapOf<PairKey, PairState>()
    private var nextTrackId = 0
    private var frameNumber = 0L
    private var crosswalkCandidate: Crosswalk? = null
    private var crosswalkCandidateFrames = 0
    private var confirmedCrosswalk: Crosswalk? = null

    override fun detect(
        detections: List<Detection>,
        leftLane: LineModel?,
        rightLane: LineModel?,
        transverseLines: List<LineModel>,
        timestampMillis: Long
    ): List<Violation> {
        frameNumber++
        updateCrosswalk(transverseLines)
        val currentTracks = updateTracks(detections)
        val activePairKeys = mutableSetOf<PairKey>()
        val violations = mutableListOf<Violation>()
        val crosswalk = confirmedCrosswalk

        if (crosswalk != null) {
            for (firstIndex in currentTracks.indices) {
                for (secondIndex in firstIndex + 1 until currentTracks.size) {
                    val first = currentTracks[firstIndex]
                    val second = currentTracks[secondIndex]
                    if (!areAdjacent(first, second)) continue

                    val key = PairKey(minOf(first.id, second.id), maxOf(first.id, second.id))
                    activePairKeys += key
                    if (abs(first.current.centerY - second.current.centerY) <
                        max(first.current.height, second.current.height) * MIN_LONGITUDINAL_SEPARATION_RATIO
                    ) continue

                    val state = pairs.getOrPut(key) { PairState() }
                    val order = if (first.current.centerY < second.current.centerY) -1 else 1
                    state.orderHistory.addLast(order)
                    if (state.orderHistory.size > PAIR_HISTORY_SIZE) state.orderHistory.removeFirst()

                    if (state.reported || state.orderHistory.size < PAIR_HISTORY_SIZE) continue
                    val previousOrderIndex = state.orderHistory.size - ORDER_CONFIRMATION_FRAMES - 1
                    val previousOrder = state.orderHistory.elementAtOrNull(previousOrderIndex) ?: continue
                    val confirmedOrder = state.orderHistory.takeLast(ORDER_CONFIRMATION_FRAMES)
                    if (previousOrder == order || confirmedOrder.any { it != order }) continue

                    val overtakingTrack = if (first.current.centerY > second.current.centerY) first else second
                    if (!overlaps(crosswalk, overtakingTrack.current)) continue

                    val detection = overtakingTrack.current.detection
                    violations += OvertakingOnCrosswalkViolation(
                        detection = detection,
                        description = "Wyprzedzanie innego pojazdu na przejściu dla pieszych",
                        timestampMillis = timestampMillis,
                        position = VehiclePosition(
                            x1 = detection.box[0],
                            y1 = detection.box[1],
                            x2 = detection.box[2],
                            y2 = detection.box[3]
                        )
                    )
                    state.reported = true
                }
            }
        }

        pairs.keys.removeAll { it !in activePairKeys }
        tracks.values.removeAll { frameNumber - it.lastSeenFrame > MAX_MISSED_FRAMES }
        return violations
    }

    private val Track.current: VehicleSample
        get() = history.last()

    private fun updateCrosswalk(lines: List<LineModel>) {
        val candidate = lines.asSequence()
            .filter { it.type == LineType.TRANSVERSE }
            .mapNotNull(::validateCrosswalk)
            .maxByOrNull { it.stripeCount }

        if (candidate == null) {
            crosswalkCandidate = null
            crosswalkCandidateFrames = 0
            confirmedCrosswalk = null
            return
        }

        val previous = crosswalkCandidate
        crosswalkCandidateFrames = if (previous != null && isSameCrosswalk(previous, candidate)) {
            crosswalkCandidateFrames + 1
        } else {
            1
        }
        crosswalkCandidate = candidate
        confirmedCrosswalk = candidate.takeIf { crosswalkCandidateFrames >= CROSSWALK_CONFIRMATION_FRAMES }
    }

    private fun validateCrosswalk(line: LineModel): Crosswalk? {
        val stripes = line.segments
            .filter { it.length >= MIN_STRIPE_LENGTH }
            .groupBy { (it.start.y + it.end.y) / 2f }
            .toSortedMap()
            .entries
            .fold(mutableListOf<MutableList<Pair<Float, Float>>>()) { groups, entry ->
                val y = entry.key
                val lastGroup = groups.lastOrNull()
                val lastCenter = lastGroup?.map { it.first }?.average()?.toFloat()
                if (lastGroup != null && lastCenter != null && y - lastCenter <= STRIPE_CLUSTER_TOLERANCE) {
                    lastGroup += entry.value.flatMap { segment ->
                        listOf(segment.start.x to y, segment.end.x to y)
                    }
                } else {
                    groups += entry.value.flatMap { segment ->
                        listOf(segment.start.x to y, segment.end.x to y)
                    }.toMutableList()
                }
                groups
            }
            .map { group ->
                val ys = group.map { it.second }
                val xs = group.map { it.first }
                (ys.average().toFloat()) to (xs.minOrNull()!! to xs.maxOrNull()!!)
            }

        if (stripes.size < MIN_CROSSWALK_STRIPES) return null
        val gaps = stripes.zipWithNext { first, second -> second.first - first.first }
        val typicalGap = gaps.sorted()[gaps.size / 2]
        if (typicalGap <= 0f || gaps.any { it !in typicalGap * MIN_GAP_RATIO..typicalGap * MAX_GAP_RATIO }) {
            return null
        }

        val commonMinX = stripes.maxOf { it.second.first }
        val commonMaxX = stripes.minOf { it.second.second }
        val narrowestStripe = stripes.minOf { it.second.second - it.second.first }
        if (commonMaxX - commonMinX < narrowestStripe * MIN_COMMON_WIDTH_RATIO) return null

        return Crosswalk(
            minX = stripes.minOf { it.second.first },
            maxX = stripes.maxOf { it.second.second },
            minY = stripes.first().first,
            maxY = stripes.last().first,
            stripeCount = stripes.size
        )
    }

    private fun isSameCrosswalk(first: Crosswalk, second: Crosswalk): Boolean =
        abs(first.centerX - second.centerX) <= max(MIN_CROSSWALK_X_SHIFT, first.width * MAX_CROSSWALK_X_SHIFT_RATIO) &&
            abs(first.centerY - second.centerY) <= max(MIN_CROSSWALK_Y_SHIFT, first.height * MAX_CROSSWALK_Y_SHIFT_RATIO)

    private fun updateTracks(detections: List<Detection>): List<Track> {
        val vehicleDetections = detections.filter { it.classId in VEHICLE_CLASS_IDS }
        val candidates = mutableListOf<Triple<Float, Int, Int>>()
        for ((trackId, track) in tracks) {
            for ((index, detection) in vehicleDetections.withIndex()) {
                val sample = sample(detection)
                val previous = track.current
                val distance = sqrt(
                    (sample.centerX - previous.centerX) * (sample.centerX - previous.centerX) +
                        (sample.centerY - previous.centerY) * (sample.centerY - previous.centerY)
                )
                val sizeDifference = abs(sample.width - previous.width) / max(sample.width, previous.width) +
                    abs(sample.height - previous.height) / max(sample.height, previous.height)
                val cost = distance / max(previous.width, previous.height) + sizeDifference
                if (cost <= MAX_MATCH_COST) candidates += Triple(cost, trackId, index)
            }
        }

        val usedTracks = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()
        for ((_, trackId, detectionIndex) in candidates.sortedBy { it.first }) {
            if (trackId in usedTracks || detectionIndex in usedDetections) continue
            val track = tracks.getValue(trackId)
            record(track, vehicleDetections[detectionIndex])
            usedTracks += trackId
            usedDetections += detectionIndex
        }

        for ((index, detection) in vehicleDetections.withIndex()) {
            if (index in usedDetections) continue
            val track = Track(nextTrackId++)
            tracks[track.id] = track
            record(track, detection)
        }
        return tracks.values.filter { it.lastSeenFrame == frameNumber }
    }

    private fun record(track: Track, detection: Detection) {
        track.history.addLast(sample(detection))
        if (track.history.size > TRACK_HISTORY_SIZE) track.history.removeFirst()
        track.lastSeenFrame = frameNumber
    }

    private fun sample(detection: Detection): VehicleSample {
        val box = detection.box
        return VehicleSample(
            detection = detection,
            centerX = (box[0] + box[2]) / 2f,
            centerY = (box[1] + box[3]) / 2f,
            width = (box[2] - box[0]).coerceAtLeast(1f),
            height = (box[3] - box[1]).coerceAtLeast(1f),
            frame = frameNumber
        )
    }

    private fun areAdjacent(first: Track, second: Track): Boolean {
        val horizontalDistance = abs(first.current.centerX - second.current.centerX)
        val maximumWidth = max(first.current.width, second.current.width)
        val minimumSeparation = (first.current.width + second.current.width) * MIN_LATERAL_SEPARATION_RATIO
        return horizontalDistance in minimumSeparation..(maximumWidth * MAX_LATERAL_SEPARATION_RATIO)
    }

    private fun overlaps(crosswalk: Crosswalk, vehicle: VehicleSample): Boolean {
        val box = vehicle.detection.box
        return box[0] <= crosswalk.maxX && box[2] >= crosswalk.minX &&
            box[1] <= crosswalk.maxY && box[3] >= crosswalk.minY
    }

    private companion object {
        val VEHICLE_CLASS_IDS = setOf(2, 3, 5, 7)
        const val MIN_STRIPE_LENGTH = 20f
        const val MIN_CROSSWALK_STRIPES = 4
        const val STRIPE_CLUSTER_TOLERANCE = 10f
        const val MIN_GAP_RATIO = 0.5f
        const val MAX_GAP_RATIO = 1.8f
        const val MIN_COMMON_WIDTH_RATIO = 0.2f
        const val MIN_CROSSWALK_X_SHIFT = 60f
        const val MIN_CROSSWALK_Y_SHIFT = 35f
        const val MAX_CROSSWALK_X_SHIFT_RATIO = 0.3f
        const val MAX_CROSSWALK_Y_SHIFT_RATIO = 0.5f
        const val MIN_LATERAL_SEPARATION_RATIO = 0.35f
        const val MAX_LATERAL_SEPARATION_RATIO = 3.5f
        const val MIN_LONGITUDINAL_SEPARATION_RATIO = 0.25f
        const val MAX_MATCH_COST = 2f
        const val MAX_MISSED_FRAMES = 3L
        const val CROSSWALK_CONFIRMATION_FRAMES = 3
        const val TRACK_HISTORY_SIZE = 5
        const val PAIR_HISTORY_SIZE = 5
        const val ORDER_CONFIRMATION_FRAMES = 2
    }
}