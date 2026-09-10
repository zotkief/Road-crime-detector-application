package com.example.roadcrimedetector.processing.tracking

const val TRACKER_TIMEOUT_MS = 1000L
const val TRACKER_HISTORY_SIZE = 5

data class BboxSnapshot(val bbox: Bbox, val timestamp: Long)

class TrackedVehicle(
    val id: Int,
    var bbox: Bbox,
    var lastSeenTime: Long
) {
    val history = ArrayDeque<BboxSnapshot>()
    var velocityX: Float = 0f
    var velocityY: Float = 0f

    init {
        history.addLast(BboxSnapshot(bbox, lastSeenTime))
    }

    fun recordDetection(newBbox: Bbox, timestamp: Long) {
        val dt = (timestamp - lastSeenTime).coerceAtLeast(1L).toFloat()
        velocityX = (newBbox.cx - bbox.cx) / dt
        velocityY = (newBbox.cy - bbox.cy) / dt
        bbox = newBbox
        lastSeenTime = timestamp
        history.addLast(BboxSnapshot(newBbox, timestamp))
        if (history.size > TRACKER_HISTORY_SIZE) history.removeFirst()
    }

    fun predictedBbox(atTime: Long): Bbox {
        val dt = (atTime - lastSeenTime).toFloat()
        val dx = velocityX * dt
        val dy = velocityY * dt
        return Bbox(bbox.x1 + dx, bbox.y1 + dy, bbox.x2 + dx, bbox.y2 + dy)
    }

    fun isStale(now: Long): Boolean = now - lastSeenTime > TRACKER_TIMEOUT_MS
}
