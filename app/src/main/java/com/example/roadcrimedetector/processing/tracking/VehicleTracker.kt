package com.example.roadcrimedetector.processing.tracking

import kotlin.math.abs
import kotlin.math.sqrt

const val MAX_MATCH_COST = 1.6f

class VehicleTracker {
    private val trackers = mutableMapOf<Int, TrackedVehicle>()
    private var nextId = 0

    fun current(): Map<Int, TrackedVehicle> = trackers

    fun update(detections: List<Bbox>, now: Long): Map<Int, TrackedVehicle> {
        trackers.values.filter { it.isStale(now) }.map { it.id }.forEach { trackers.remove(it) }

        data class Candidate(val trackerId: Int, val detIdx: Int, val cost: Float)
        val candidates = mutableListOf<Candidate>()
        for (tracker in trackers.values) {
            val predicted = tracker.predictedBbox(now)
            for ((detIdx, det) in detections.withIndex()) {
                val cost = matchCost(predicted, det)
                if (cost <= MAX_MATCH_COST) candidates.add(Candidate(tracker.id, detIdx, cost))
            }
        }

        candidates.sortBy { it.cost }
        val usedTrackers = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()
        for (candidate in candidates) {
            if (candidate.trackerId in usedTrackers || candidate.detIdx in usedDetections) continue
            trackers[candidate.trackerId]?.recordDetection(detections[candidate.detIdx], now)
            usedTrackers.add(candidate.trackerId)
            usedDetections.add(candidate.detIdx)
        }

        for ((detIdx, det) in detections.withIndex()) {
            if (detIdx !in usedDetections) {
                val id = nextId++
                trackers[id] = TrackedVehicle(id, det, now)
            }
        }
        return trackers
    }

    private fun matchCost(predicted: Bbox, detected: Bbox): Float {
        val predArea = ((predicted.x2 - predicted.x1) * (predicted.y2 - predicted.y1)).coerceAtLeast(1f)
        val detArea = ((detected.x2 - detected.x1) * (detected.y2 - detected.y1)).coerceAtLeast(1f)
        val dx = predicted.cx - detected.cx
        val dy = predicted.cy - detected.cy
        val centerDist = sqrt(dx * dx + dy * dy)
        val normDist = centerDist / sqrt(maxOf(predArea, detArea))
        val sizeDiff = abs(predArea - detArea) / maxOf(predArea, detArea)
        return normDist + sizeDiff
    }
}
