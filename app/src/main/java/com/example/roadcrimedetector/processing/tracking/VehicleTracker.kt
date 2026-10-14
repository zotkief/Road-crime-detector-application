package com.example.roadcrimedetector.processing.tracking

import com.example.roadcrimedetector.model.Detection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

const val MAX_MATCH_COST = 1.6f

/** Metoda wyboru par track <-> detekcja. */
enum class TrackingMethod {
    /** Szybka: sortowanie kandydatów po koszcie i branie od najtańszego. */
    GREEDY,

    /** Optymalna: algorytm węgierski gdy max(tracki, detekcje) < 10, w przeciwnym razie GREEDY. */
    HUNGARIAN
}

private const val HUNGARIAN_MAX_SIZE = 10
private const val FORBIDDEN_COST = 1000f
private const val CONFIDENT_COST = 0.5f
private const val MIN_COMPENSATION_PAIRS = 3

class VehicleTracker(
    private val method: TrackingMethod = TrackingMethod.HUNGARIAN,
    /**
     * Zakaz dopasowań między klasami (auto/ciężarówka/pieszy...). Domyślnie dopuszcza wszystko,
     * bo nie znam pól klasy `Detection`. Przykład: { a, b -> a.classId == b.classId }.
     */
    private val sameClass: (Detection, Detection) -> Boolean = { _, _ -> true }
) {
    private val trackers = mutableMapOf<Int, TrackedVehicle>()
    private var nextId = 0

    /** Migawka potwierdzonych tracków (niemutowalna, bezpieczna do iteracji). */
    private var visible: Map<Int, TrackedVehicle> = emptyMap()

    fun current(): Map<Int, TrackedVehicle> = visible

    /** Zwraca tylko potwierdzone tracki. Niepotwierdzone nie trafiają do reguł wykroczeń. */
    fun update(detections: List<Detection>, now: Long): Map<Int, TrackedVehicle> {
        val trackList = trackers.values.toList()
        val rows = trackList.size
        val cols = detections.size
        val trackMatched = BooleanArray(rows)
        val detectionUsed = BooleanArray(cols)

        if (rows > 0 && cols > 0) {
            val detBoxes = Array(cols) { detections[it].toBbox() }
            val predicted = Array(rows) { trackList[it].predictedBbox(now) }

            var cost = buildCostMatrix(trackList, predicted, detections, detBoxes, 0f, 0f)

            // Wspólne przesunięcie wszystkich tracków (skręt, próg zwalniający) liczymy z pewnych par
            // i kompensujemy je w drugim przebiegu.
            val shift = estimateCameraShift(predicted, detBoxes, cost, rows, cols)
            var shiftX = 0f
            var shiftY = 0f
            if (shift != null) {
                shiftX = shift.x
                shiftY = shift.y
                cost = buildCostMatrix(trackList, predicted, detections, detBoxes, shiftX, shiftY)
            }

            val assignment = when (method) {
                TrackingMethod.GREEDY -> assignGreedy(cost, rows, cols)
                TrackingMethod.HUNGARIAN -> assignHungarianOrGreedy(cost, rows, cols)
            }

            for (row in 0 until rows) {
                val col = assignment[row]
                if (col < 0 || cost[row * cols + col] > MAX_MATCH_COST) continue
                trackList[row].recordDetection(detections[col], now, shiftX, shiftY)
                trackMatched[row] = true
                detectionUsed[col] = true
            }
        }

        for (row in 0 until rows) {
            if (!trackMatched[row]) trackList[row].markMissed()
        }
        for (col in 0 until cols) {
            if (!detectionUsed[col]) {
                val id = nextId++
                trackers[id] = TrackedVehicle(id, detections[col], now)
            }
        }

        // Czyszczenie PO dopasowaniu: track, który właśnie wygasa, ma jeszcze szansę na odnowienie.
        trackers.values.removeAll { it.isStale(now) }

        visible = trackers.filterValues { it.isConfirmed }
        return visible
    }

    // ---------------------------------------------------------------- przypisanie

    /** Sortuje wszystkie pary w bramce po koszcie i bierze od najtańszej. */
    private fun assignGreedy(cost: FloatArray, rows: Int, cols: Int): IntArray {
        val result = IntArray(rows) { -1 }
        val order = ArrayList<Int>()
        for (i in cost.indices) {
            if (cost[i] <= MAX_MATCH_COST) order.add(i)
        }
        order.sortBy { cost[it] }

        val rowUsed = BooleanArray(rows)
        val colUsed = BooleanArray(cols)
        for (index in order) {
            val row = index / cols
            val col = index % cols
            if (rowUsed[row] || colUsed[col]) continue
            result[row] = col
            rowUsed[row] = true
            colUsed[col] = true
        }
        return result
    }

    /** Węgierski dla małych scen (n < 10), zachłanny dla większych. */
    private fun assignHungarianOrGreedy(cost: FloatArray, rows: Int, cols: Int): IntArray =
        if (max(rows, cols) < HUNGARIAN_MAX_SIZE) {
            HungarianAlgorithm.solve(cost, rows, cols)
        } else {
            assignGreedy(cost, rows, cols)
        }

    // ---------------------------------------------------------------- koszty

    /**
     * Macierz kosztów rows x cols. Pary poza bramką, z NaN lub z różnych klas dostają FORBIDDEN_COST,
     * dzięki czemu solver węgierski w ogóle ich nie preferuje.
     */
    private fun buildCostMatrix(
        trackList: List<TrackedVehicle>,
        predicted: Array<Bbox>,
        detections: List<Detection>,
        detBoxes: Array<Bbox>,
        shiftX: Float,
        shiftY: Float
    ): FloatArray {
        val rows = predicted.size
        val cols = detBoxes.size
        val cost = FloatArray(rows * cols)
        for (row in 0 until rows) {
            val p = predicted[row]
            val shifted = if (shiftX == 0f && shiftY == 0f) {
                p
            } else {
                Bbox(p.x1 + shiftX, p.y1 + shiftY, p.x2 + shiftX, p.y2 + shiftY)
            }
            val lastDetection = trackList[row].history.lastOrNull()?.detection
            for (col in 0 until cols) {
                val raw = matchCost(shifted, detBoxes[col])
                val classMismatch = lastDetection != null && !sameClass(lastDetection, detections[col])
                cost[row * cols + col] =
                    if (raw.isNaN() || raw > MAX_MATCH_COST || classMismatch) FORBIDDEN_COST else raw
            }
        }
        return cost
    }

    /** Odległość punktów styku (cx, y2) znormalizowana rozmiarem + różnica rozmiaru. */
    private fun matchCost(predicted: Bbox, detected: Bbox): Float {
        val predArea = ((predicted.x2 - predicted.x1) * (predicted.y2 - predicted.y1)).coerceAtLeast(1f)
        val detArea = ((detected.x2 - detected.x1) * (detected.y2 - detected.y1)).coerceAtLeast(1f)
        val dx = predicted.cx - detected.cx
        val dy = predicted.y2 - detected.y2
        val centerDist = sqrt(dx * dx + dy * dy)
        val normDist = centerDist / sqrt(max(predArea, detArea))
        val sizeDiff = abs(predArea - detArea) / max(predArea, detArea)
        return normDist + sizeDiff
    }

    // ---------------------------------------------------------------- ruch kamery

    private class Shift(val x: Float, val y: Float)

    /**
     * Mediana residuum (detekcja minus predykcja) po parach, które są wzajemnie najlepsze i mają
     * niski koszt. Zwraca null, gdy par jest za mało, żeby ufać estymacie.
     */
    private fun estimateCameraShift(
        predicted: Array<Bbox>,
        detBoxes: Array<Bbox>,
        cost: FloatArray,
        rows: Int,
        cols: Int
    ): Shift? {
        val bestColForRow = IntArray(rows) { -1 }
        val bestRowForCol = IntArray(cols) { -1 }
        for (row in 0 until rows) {
            var best = Float.MAX_VALUE
            for (col in 0 until cols) {
                val c = cost[row * cols + col]
                if (c < best) {
                    best = c
                    bestColForRow[row] = col
                }
            }
        }
        for (col in 0 until cols) {
            var best = Float.MAX_VALUE
            for (row in 0 until rows) {
                val c = cost[row * cols + col]
                if (c < best) {
                    best = c
                    bestRowForCol[col] = row
                }
            }
        }

        val dxs = FloatArray(rows)
        val dys = FloatArray(rows)
        var count = 0
        for (row in 0 until rows) {
            val col = bestColForRow[row]
            if (col < 0 || bestRowForCol[col] != row) continue
            if (cost[row * cols + col] > CONFIDENT_COST) continue
            dxs[count] = detBoxes[col].cx - predicted[row].cx
            dys[count] = detBoxes[col].y2 - predicted[row].y2
            count++
        }
        if (count < MIN_COMPENSATION_PAIRS) return null
        return Shift(median(dxs, count), median(dys, count))
    }

    private fun median(values: FloatArray, count: Int): Float {
        values.sort(0, count)
        val middle = count / 2
        return if (count % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2f
    }

    private fun Detection.toBbox() = Bbox(box[0], box[1], box[2], box[3])
}