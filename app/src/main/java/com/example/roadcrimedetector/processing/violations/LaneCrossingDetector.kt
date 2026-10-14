package com.example.roadcrimedetector.processing.violations

import com.example.roadcrimedetector.model.LaneCrossingViolation
import com.example.roadcrimedetector.model.Violation
import com.example.roadcrimedetector.model.VehiclePosition
import com.example.roadcrimedetector.processing.lanes.LineModel
import com.example.roadcrimedetector.processing.lanes.LineType
import com.example.roadcrimedetector.processing.tracking.TrackedVehicle
import kotlin.math.abs
import kotlin.math.sqrt
import android.util.Log

/**
 * Wykrywa przekroczenie linii, przez które przejeżdżanie jest zabronione.
 *
 * Podstawa prawna (rozporządzenie w sprawie znaków i sygnałów drogowych, § 86; zweryfikuj aktualny tekst jednolity):
 *  - P-2 (pojedyncza ciągła) i P-4 (podwójna ciągła): zakaz przejeżdżania i najeżdżania, z obu stron,
 *  - P-3 (jednostronnie przekraczalna): zakaz przejeżdżania od strony linii ciągłej,
 *    z wyjątkiem powrotu po wyprzedzaniu na pas położony przy linii przerywanej,
 *  - P-1 (przerywana): przekraczanie dozwolone.
 *
 * Konwencje (układ obrazu):
 *  - znak odległości > 0 => pojazd po LEWEJ stronie linii, < 0 => po PRAWEJ,
 *  - LineType.RIGHT_CONTINOUS = linia ciągła leży po prawej stronie znaku (przerywana po lewej),
 *    czyli zakazany jest przejazd z prawej na lewą; LEFT_CONTINOUS analogicznie w drugą stronę.
 *    Jeśli etykiety w datasecie mają odwrotne znaczenie, zmień wyłącznie [restrictionOf].
 *
 * Świadome ograniczenia:
 *  - nie rozróżnia P-2 / P-4 / P-7b (wszystkie to "ciągła"),
 *  - nie zna wyjątków (omijanie przeszkody, polecenia policjanta, sygnały) - wynik to kandydat do weryfikacji,
 *  - nie wykrywa przekroczeń linii przez pojazd z kamerą (ego), bo nie jest śledzony,
 *  - mierzy przejechanie punktu styku (środek dolnej krawędzi), a nie samo najechanie kołem.
 */
class LaneCrossingDetector : ViolationRule() {

    private inline fun dbg(msg: () -> String) {
        if (DEBUG) Log.d(TAG, msg())
    }

    private enum class Lane { LEFT, RIGHT }

    /** Z której strony linii (w układzie obrazu) przejazd jest zabroniony. */
    private enum class Restriction { NONE, BOTH, FROM_LEFT, FROM_RIGHT }

    private class TypeVote(val timestampMillis: Long, val restriction: Restriction)

    /** Stan jednej linii (lewej/prawej), wspólny dla wszystkich pojazdów. */
    private class LaneContext {
        val votes = ArrayDeque<TypeVote>()
        var lastSeenMillis: Long? = null
        var previousModel: LineModel? = null
    }

    /** Stan jednego pojazdu względem jednej linii. */
    private class LaneState {
        var side = 0
        var pendingSide = 0
        var pendingFrames = 0
        var pendingSinceMillis = 0L
        var lastCrossingMillis: Long? = null
        var lastCrossingFromSide = 0
        var lastReportedMillis: Long? = null

        fun clearPending() {
            pendingSide = 0
            pendingFrames = 0
        }

        fun resetGeometry(newSide: Int = 0) {
            side = newSide
            clearPending()
        }
    }

    private class TrackState {
        val lanes = mutableMapOf<Lane, LaneState>()
        var lastDetection: Any? = null
    }

    private class Candidate(val lane: Lane, val violation: Violation)

    private val lanes = Lane.values()
    private val laneContexts = mapOf(Lane.LEFT to LaneContext(), Lane.RIGHT to LaneContext())
    private val crossingStates = mutableMapOf<Int, TrackState>()

    override fun detect(
        leftLane: LineModel?,
        rightLane: LineModel?,
        transverseLines: List<LineModel>,
        timestampMillis: Long,
        trackedVehicles: Map<Int, TrackedVehicle>
    ): List<Violation> {
        val candidates = ArrayList<Candidate>(2)

        for (lane in lanes) {
            val line = if (lane == Lane.LEFT) leftLane else rightLane
            val context = laneContexts.getValue(lane)
            val restriction = updateContext(lane, context, line, timestampMillis)
            if (line == null || restriction == null) continue

            for ((trackId, vehicle) in trackedVehicles) {
                val detection = vehicle.history.lastOrNull()?.detection ?: continue
                val track = crossingStates.getOrPut(trackId) { TrackState() }

                // Ta sama detekcja co w poprzedniej klatce = tracker tylko ekstrapoluje pozycję.
                // Nie liczymy takiej klatki do potwierdzenia przekroczenia.
                if (track.lastDetection === detection) {
                    dbg { "[$timestampMillis] id=$trackId SKIP extrapolated" }
                    continue
                }

                val box = vehicle.bbox
                val width = box.x2 - box.x1
                if (width < MIN_VEHICLE_WIDTH_PX) {
                    dbg { "[$timestampMillis] id=$trackId SKIP width=$width" }
                    continue
                }

                val state = track.lanes.getOrPut(lane) { LaneState() }
                val fromSide = processVehicle(
                    line = line,
                    previousModel = context.previousModel,
                    restriction = restriction,
                    state = state,
                    x = box.cx,
                    y = box.y2,
                    width = width,
                    timestampMillis = timestampMillis
                )
                if (fromSide != 0) {
                    candidates += Candidate(
                        lane,
                        LaneCrossingViolation(
                            detection = detection,
                            description = describe(lane, restriction, fromSide),
                            timestampMillis = timestampMillis,
                            position = VehiclePosition(
                                x1 = detection.box[0],
                                y1 = detection.box[1],
                                x2 = detection.box[2],
                                y2 = detection.box[3]
                            )
                        )
                    )
                }
            }
            context.previousModel = line
        }

        for ((trackId, vehicle) in trackedVehicles) {
            crossingStates[trackId]?.lastDetection = vehicle.history.lastOrNull()?.detection
        }
        crossingStates.keys.retainAll(trackedVehicles.keys)

        if (candidates.isEmpty()) return emptyList()

        // Jeśli wiele pojazdów "przekracza" tę samą linię w jednej klatce, to prawie na pewno
        // skoczył model linii (zmiana pasa ego, błędne dopasowanie), a nie zachowanie pojazdów.
        val perLane = candidates.groupingBy { it.lane }.eachCount()
        dbg { "[$timestampMillis] candidates=$perLane limit=$MASS_CROSSING_LIMIT" }
        return candidates
            .filter { perLane.getValue(it.lane) < MASS_CROSSING_LIMIT }
            .map { it.violation }
    }

    /**
     * Aktualizuje kontekst linii. Zwraca obowiązujący zakaz albo null, gdy linia jest
     * niedostępna (brak, poprzeczna, przejście dla pieszych).
     */
    private fun updateContext(
        lane: Lane,
        context: LaneContext,
        line: LineModel?,
        timestampMillis: Long
    ): Restriction? {
        if (line == null ||
            line.segments.isEmpty() ||
            line.type == LineType.TRANSVERSE ||
            line.type == LineType.CROSSING
        ) {
            val lastSeen = context.lastSeenMillis
            if (lastSeen != null && timestampMillis - lastSeen > LINE_LOST_GRACE_MS) {
                resetLane(lane, context)
            }
            dbg { "[$timestampMillis] $lane ctx: UNAVAILABLE line=${line?.type} segs=${line?.segments?.size}" }
            return null
        }

        context.lastSeenMillis = timestampMillis
        context.votes.addLast(TypeVote(timestampMillis, restrictionOf(line.type)))
        while (context.votes.isNotEmpty() &&
            timestampMillis - context.votes.first().timestampMillis > TYPE_VOTE_WINDOW_MS
        ) {
            context.votes.removeFirst()
        }
        val r = effectiveRestriction(context)
        dbg { "[$timestampMillis] $lane ctx: type=${line.type} segs=${line.segments.size} " +
            "votes=${context.votes.size} -> restriction=$r" }
        return r
    }

    private fun resetLane(lane: Lane, context: LaneContext) {
        context.votes.clear()
        context.previousModel = null
        context.lastSeenMillis = null
        // Strony zerujemy, ale historię ostatniego przekroczenia zostawiamy (wyjątek P-3).
        for (track in crossingStates.values) track.lanes[lane]?.resetGeometry()
    }

    private fun restrictionOf(type: LineType): Restriction = when (type) {
        LineType.SOLID, LineType.CONTINUOUS -> Restriction.BOTH
        LineType.RIGHT_CONTINOUS -> Restriction.FROM_RIGHT
        LineType.LEFT_CONTINOUS -> Restriction.FROM_LEFT
        else -> Restriction.NONE
    }

    /**
     * Typ linii jest głosowany w oknie czasowym, bo klasyfikacja per klatka migocze.
     * Wątpliwość (brak wyraźnego zwycięzcy) oznacza brak zakazu: lepiej przeoczyć niż fałszywie oskarżyć.
     */
    private fun effectiveRestriction(context: LaneContext): Restriction {
        val votes = context.votes
        if (votes.size < MIN_TYPE_VOTES) return Restriction.NONE

        var both = 0
        var fromLeft = 0
        var fromRight = 0
        for (vote in votes) {
            when (vote.restriction) {
                Restriction.BOTH -> both++
                Restriction.FROM_LEFT -> fromLeft++
                Restriction.FROM_RIGHT -> fromRight++
                Restriction.NONE -> Unit
            }
        }
        if ((both + fromLeft + fromRight).toFloat() / votes.size < RESTRICTIVE_VOTE_SHARE) {
            return Restriction.NONE
        }
        return when {
            both > fromLeft && both > fromRight -> Restriction.BOTH
            fromLeft > both && fromLeft > fromRight -> Restriction.FROM_LEFT
            fromRight > both && fromRight > fromLeft -> Restriction.FROM_RIGHT
            else -> Restriction.NONE
        }
    }

    /**
     * Zwraca stronę, z której pojazd przyjechał, gdy należy zgłosić wykroczenie, w przeciwnym razie 0.
     */
    private fun processVehicle(
        line: LineModel,
        previousModel: LineModel?,
        restriction: Restriction,
        state: LaneState,
        x: Float,
        y: Float,
        width: Float,
        timestampMillis: Long
    ): Int {
        // Progi skalujemy szerokością pojazdu (~1,8 m): daje to skalę świata na danej głębokości.
        val edgeTolerance = (width * EDGE_CLUSTER_RATIO).coerceIn(MIN_EDGE_CLUSTER_PX, MAX_EDGE_CLUSTER_PX)
        val hysteresis = (width * HYSTERESIS_RATIO).coerceIn(MIN_HYSTERESIS_PX, MAX_HYSTERESIS_PX)

        val distance = signedDistance(x, y, line, edgeTolerance)
        if (distance == null) {
            dbg { "[$timestampMillis] SKIP no distance (x=$x y=$y segs=${line.segments.size})" }
            return 0
        }

        // Skok modelu linii: ten sam punkt względem starego i nowego modelu różni się o ponad
        // ~0,35 szerokości pojazdu. To nie jest ruch pojazdu, więc ustalamy stronę od nowa.
        if (previousModel != null) {
            val previousDistance = signedDistance(x, y, previousModel, edgeTolerance)
            if (previousDistance != null && abs(distance - previousDistance) > MODEL_JUMP_RATIO * width) {
                state.resetGeometry(classifySide(distance, hysteresis, 0))
                return 0
            }
        }

        val side = classifySide(distance, hysteresis, state.side)
        dbg { "[$timestampMillis] x=$x y=$y w=$width dist=%.1f hyst=%.1f side=$side state.side=${state.side} pending=${state.pendingSide}/${state.pendingFrames}".format(distance, hysteresis) }
        if (state.side == 0) {
            state.side = side
            state.clearPending()
            return 0
        }
        if (side == state.side) {
            state.clearPending()
            return 0
        }

        if (state.pendingSide != side) {
            state.pendingSide = side
            state.pendingFrames = 1
            state.pendingSinceMillis = timestampMillis
        } else {
            state.pendingFrames++
        }
        // Potwierdzenie liczone jednocześnie w klatkach i w czasie (FPS na telefonie jest zmienny).
        if (state.pendingFrames < CROSSING_CONFIRMATION_FRAMES ||
            timestampMillis - state.pendingSinceMillis < CROSSING_CONFIRMATION_MS
        ) {
            return 0
        }

        val from = state.side
        state.side = side
        state.clearPending()

        val forbidden = isForbidden(restriction, from)
        // Wyjątek P-3: powrót po wyprzedzaniu. Dotyczy tylko linii jednostronnie przekraczalnej.
        val returningAfterOvertaking = restriction != Restriction.BOTH &&
            isReturnAfterOvertaking(state, side, timestampMillis)
        val lastReported = state.lastReportedMillis
        val coolingDown = lastReported != null && timestampMillis - lastReported < REPORT_COOLDOWN_MS

        state.lastCrossingMillis = timestampMillis
        state.lastCrossingFromSide = from
        dbg { "[$timestampMillis] CROSSING from=$from restriction=$restriction forbidden=$forbidden " +
            "returnOvertake=$returningAfterOvertaking cooldown=$coolingDown" }
        if (!forbidden || returningAfterOvertaking || coolingDown) return 0
        state.lastReportedMillis = timestampMillis
        return from
    }

    private fun isForbidden(restriction: Restriction, fromSide: Int): Boolean = when (restriction) {
        Restriction.NONE -> false
        Restriction.BOTH -> true
        Restriction.FROM_LEFT -> fromSide == LEFT_OF_LINE
        Restriction.FROM_RIGHT -> fromSide == RIGHT_OF_LINE
    }

    /** Wcześniej ten sam pojazd przeszedł z tej strony, na którą teraz wraca. */
    private fun isReturnAfterOvertaking(state: LaneState, toSide: Int, timestampMillis: Long): Boolean {
        val last = state.lastCrossingMillis ?: return false
        return state.lastCrossingFromSide == toSide && timestampMillis - last <= RETURN_AFTER_OVERTAKING_MS
    }

    /**
     * Odległość ze znakiem punktu od osi linii. Kontur namalowanej linii daje odległości do obu
     * krawędzi (a podwójna linia do czterech): klastrujemy je, odrzucamy klastry o małej liczności
     * i bierzemy medianę środków klastrów, czyli w praktyce oś znaku.
     */
    private fun signedDistance(x: Float, y: Float, line: LineModel, edgeTolerance: Float): Float? {
        val segments = line.segments
        val distances = FloatArray(segments.size)
        var n = 0
        for (segment in segments) {
            val dx = segment.end.x - segment.start.x
            val dy = segment.end.y - segment.start.y
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0f) continue
            // Prawie poziome segmenty (artefakty konturu) mają niestabilny znak.
            if (abs(dy) < NEAR_HORIZONTAL_RATIO * abs(dx)) continue

            val projection = ((x - segment.start.x) * dx + (y - segment.start.y) * dy) / lengthSquared
            if (projection < -SEGMENT_MARGIN || projection > 1f + SEGMENT_MARGIN) continue

            val orientation = if (dy >= 0f) 1f else -1f
            distances[n++] = orientation * (dx * (y - segment.start.y) - dy * (x - segment.start.x)) /
                sqrt(lengthSquared)
        }
        if (n == 0) return null
        distances.sort(0, n)

        val centers = FloatArray(n)
        val counts = IntArray(n)
        var clusters = 0
        for (i in 0 until n) {
            val d = distances[i]
            if (clusters > 0 && d - centers[clusters - 1] <= edgeTolerance) {
                val last = clusters - 1
                centers[last] = (centers[last] * counts[last] + d) / (counts[last] + 1)
                counts[last]++
            } else {
                centers[clusters] = d
                counts[clusters] = 1
                clusters++
            }
        }

        var maxCount = 0
        for (i in 0 until clusters) if (counts[i] > maxCount) maxCount = counts[i]
        val minCount = (maxCount * MIN_CLUSTER_SHARE).toInt().coerceAtLeast(1)

        var kept = 0
        for (i in 0 until clusters) {
            if (counts[i] >= minCount) centers[kept++] = centers[i]
        }
        val middle = kept / 2
        return if (kept % 2 == 1) centers[middle] else (centers[middle - 1] + centers[middle]) / 2f
    }

    private fun classifySide(distance: Float, hysteresis: Float, previousSide: Int): Int = when {
        distance < -hysteresis -> RIGHT_OF_LINE
        distance > hysteresis -> LEFT_OF_LINE
        else -> previousSide
    }

    private fun describe(lane: Lane, restriction: Restriction, fromSide: Int): String {
        val lineName = if (restriction == Restriction.BOTH) {
            "ciągłej"
        } else {
            "jednostronnie przekraczalnej (od strony linii ciągłej)"
        }
        val direction = if (fromSide == LEFT_OF_LINE) "z lewej na prawą" else "z prawej na lewą"
        return "Przekroczenie ${laneDescription(lane)} linii $lineName ($direction)"
    }

    private fun laneDescription(lane: Lane): String = when (lane) {
        Lane.LEFT -> "lewej"
        Lane.RIGHT -> "prawej"
    }

    private companion object {

        const val TAG = "LaneCross"
        const val DEBUG = true
        const val LEFT_OF_LINE = 1
        const val RIGHT_OF_LINE = -1

        // Progi względne (ułamek szerokości bboxa pojazdu), z ograniczeniem w pikselach.
        const val HYSTERESIS_RATIO = 0.05f
        const val MIN_HYSTERESIS_PX = 3f
        const val MAX_HYSTERESIS_PX = 24f
        const val EDGE_CLUSTER_RATIO = 0.03f
        const val MIN_EDGE_CLUSTER_PX = 2f
        const val MAX_EDGE_CLUSTER_PX = 12f
        const val MODEL_JUMP_RATIO = 0.35f
        const val MIN_VEHICLE_WIDTH_PX = 16f

        // Geometria konturu.
        const val SEGMENT_MARGIN = 0.15f
        const val NEAR_HORIZONTAL_RATIO = 0.25f
        const val MIN_CLUSTER_SHARE = 0.34f

        // Potwierdzenie (klatki ORAZ czas).
        const val CROSSING_CONFIRMATION_FRAMES = 2
        const val CROSSING_CONFIRMATION_MS = 150L

        // Stabilizacja linii i typu.
        const val LINE_LOST_GRACE_MS = 700L
        const val TYPE_VOTE_WINDOW_MS = 1000L
        const val MIN_TYPE_VOTES = 1
        const val RESTRICTIVE_VOTE_SHARE = 0.6f

        // Zgłaszanie.
        const val REPORT_COOLDOWN_MS = 4000L
        const val RETURN_AFTER_OVERTAKING_MS = 20000L
        const val MASS_CROSSING_LIMIT = 3
    }
}