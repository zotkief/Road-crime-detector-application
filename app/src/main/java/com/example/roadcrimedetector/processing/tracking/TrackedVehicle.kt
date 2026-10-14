package com.example.roadcrimedetector.processing.tracking

import com.example.roadcrimedetector.model.Detection
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

// Parametry dobrane pod krok przetwarzania ~200 ms (5 Hz).
const val TRACKER_TIMEOUT_MS = 700L          // potwierdzony track przeżywa ok. 3 chybione kroki
const val TRACKER_HISTORY_SIZE = 5
const val TRACKER_MIN_HITS_TO_CONFIRM = 2    // 2 kolejne trafienia = ~400 ms opóźnienia
const val TRACKER_TENTATIVE_MAX_MISSED = 0   // niepotwierdzony track ginie po pierwszym chybieniu

private const val MAX_PREDICT_MS = 400L      // ekstrapolujemy najwyżej 2 kroki
private const val COAST_DAMPING = 0.7f       // tłumienie prędkości na każdy chybiony krok
private const val MIN_DT_MS = 50L
private const val MAX_DT_MS = 600L
private const val VELOCITY_EMA_ALPHA = 0.6f  // duży krok czasu => nowy pomiar ma duży udział
private const val RESIDUAL_REINIT = 1.5f     // residuum > 1,5 szerokości => nie mieszamy z historią
private const val MIN_PREDICTED_SCALE = 0.5f
private const val MAX_PREDICTED_SCALE = 2f

data class BboxSnapshot(
    val bbox: Bbox,
    val timestamp: Long,
    val detection: Detection
)

class TrackedVehicle(
    val id: Int,
    detection: Detection,
    initialTime: Long
) {
    var lastSeenTime: Long = initialTime
        private set

    val history = ArrayDeque<BboxSnapshot>()

    /** Ostatni POMIAR (nie predykcja): spójny z history.last().detection. */
    var bbox: Bbox = detection.toBbox()
        private set

    /** Prędkość punktu styku z jezdnią: środek dolnej krawędzi (cx, y2), px/ms. */
    var velocityX: Float = 0f
        private set
    var velocityY: Float = 0f
        private set

    /** Zmiana logarytmu szerokości na ms (zbliżanie/oddalanie się pojazdu). */
    var scaleVelocity: Float = 0f
        private set

    var hits: Int = 1
        private set

    /** Liczba kolejnych kroków bez dopasowanej detekcji (0 = świeży pomiar). */
    var missedFrames: Int = 0
        private set

    val isConfirmed: Boolean get() = hits >= TRACKER_MIN_HITS_TO_CONFIRM
    val isCoasting: Boolean get() = missedFrames > 0

    init {
        history.addLast(BboxSnapshot(bbox, initialTime, detection))
    }

    /**
     * @param cameraShiftX/Y wspólne przesunięcie wszystkich tracków w tym kroku (ruch kamery, px).
     *        Odejmujemy je od pomiaru, żeby nie trafiło do prędkości pojazdu.
     */
    fun recordDetection(
        detection: Detection,
        timestamp: Long,
        cameraShiftX: Float = 0f,
        cameraShiftY: Float = 0f
    ) {
        val newBbox = detection.toBbox()
        val rawDt = timestamp - lastSeenTime

        if (rawDt in MIN_DT_MS..MAX_DT_MS) {
            val dt = rawDt.toFloat()
            val oldWidth = max(bbox.x2 - bbox.x1, 1f)
            val newWidth = max(newBbox.x2 - newBbox.x1, 1f)

            val measuredVx = (newBbox.cx - bbox.cx - cameraShiftX) / dt
            val measuredVy = (newBbox.y2 - bbox.y2 - cameraShiftY) / dt
            val measuredVs = ln(newWidth / oldWidth) / dt

            // Residuum względem (skompensowanej) predykcji, w szerokościach pojazdu.
            val predicted = predictedBbox(timestamp)
            val residual = hypot(
                newBbox.cx - (predicted.cx + cameraShiftX),
                newBbox.y2 - (predicted.y2 + cameraShiftY)
            ) / max(oldWidth, newWidth)

            // Pierwszy pomiar prędkości albo podejrzany skok (zamiana ID?): bierzemy pomiar w całości.
            val alpha = if (hits < 2 || residual > RESIDUAL_REINIT) 1f else VELOCITY_EMA_ALPHA
            velocityX += alpha * (measuredVx - velocityX)
            velocityY += alpha * (measuredVy - velocityY)
            scaleVelocity += alpha * (measuredVs - scaleVelocity)
        } else {
            velocityX = 0f
            velocityY = 0f
            scaleVelocity = 0f
        }

        bbox = newBbox
        lastSeenTime = timestamp
        hits++
        missedFrames = 0
        history.addLast(BboxSnapshot(newBbox, timestamp, detection))
        if (history.size > TRACKER_HISTORY_SIZE) history.removeFirst()
    }

    fun markMissed() {
        missedFrames++
    }

    /**
     * Predykcja zakotwiczona w punkcie styku (dolny środek): przesunięcie + skala.
     * Horyzont jest ograniczony, a prędkość tłumiona przy coastingu.
     */
    fun predictedBbox(atTime: Long): Bbox {
        val dt = (atTime - lastSeenTime).coerceIn(0L, MAX_PREDICT_MS).toFloat()
        val damping = COAST_DAMPING.pow(missedFrames)

        val width = max(bbox.x2 - bbox.x1, 1f)
        val height = max(bbox.y2 - bbox.y1, 1f)
        val scale = exp(scaleVelocity * dt * damping).coerceIn(MIN_PREDICTED_SCALE, MAX_PREDICTED_SCALE)

        val cx = bbox.cx + velocityX * dt * damping
        val bottom = bbox.y2 + velocityY * dt * damping
        val newWidth = width * scale
        val newHeight = height * scale
        return Bbox(cx - newWidth / 2f, bottom - newHeight, cx + newWidth / 2f, bottom)
    }

    fun isStale(now: Long): Boolean =
        if (isConfirmed) {
            now - lastSeenTime > TRACKER_TIMEOUT_MS
        } else {
            missedFrames > TRACKER_TENTATIVE_MAX_MISSED
        }

    private fun Detection.toBbox() = Bbox(box[0], box[1], box[2], box[3])
}