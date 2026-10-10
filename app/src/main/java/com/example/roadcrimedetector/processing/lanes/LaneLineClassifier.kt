package com.example.roadcrimedetector.processing.lanes

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.core.CvType
import org.opencv.core.Mat

data class LaneClassification(
    val leftLane: LineModel?,
    val rightLane: LineModel?,
    val displayMask: ClassifiedLaneMask,
    val crosswalk: CrosswalkInfo? = null
)

/**
 * Maska (bez dziur, zaznacza linię razem z przerwami) służy do LOKALIZACJI linii:
 * tracker + krzywa. O tym, czy linia jest ciągła czy przerywana, decyduje KOLOR
 * w klatce: wzdłuż krzywej liczymy kontrast jasności kreski względem asfaltu
 * obok i szukamy w tym profilu "schodów" (kreska / przerwa).
 *
 * [frameIsBgr] - true, jeśli Mat ma kolejność kanałów BGR (VideoCapture, imread),
 * false dla RGB/RGBA (np. Utils.bitmapToMat).
 */
class LaneLineClassifier(private val frameIsBgr: Boolean = false) {
    private val leftHistory = ClassificationHistory()
    private val rightHistory = ClassificationHistory()
    private val redIndex = if (frameIsBgr) 2 else 0
    private val crosswalkDetector = CrosswalkDetector(redIndex)
    private val crosswalkHistory = ArrayDeque<Boolean>()
    private var lastCrosswalkInfo: CrosswalkInfo? = null
    private var lastCrosswalkMask: PixelMask? = null

    fun classify(mask: PixelMask, frame: Mat): LaneClassification {
        require(!frame.empty()) { "Cannot classify lanes for an empty frame" }
        require(frame.depth() == CvType.CV_8U) { "Frame must be 8-bit" }
        val frameWidth = frame.cols()
        val frameHeight = frame.rows()
        require(frameWidth > 0 && frameHeight > 0)
        val channels = frame.channels()
        val crosswalkResult = crosswalkDetector.detect(mask, frame, channels)
        val detectedCrosswalk = crosswalkResult.crosswalks
            .takeIf { it.isNotEmpty() }
            ?.let { crosswalks ->
                CrosswalkInfo(
                    yTop = crosswalks.minOf { it.yTop },
                    yBottom = crosswalks.maxOf { it.yBottom }
                )
            }
        if (detectedCrosswalk != null) lastCrosswalkMask = crosswalkResult.crosswalkMask
        val crosswalkInfo = updateCrosswalkHistory(detectedCrosswalk)
        val workingMask = crosswalkResult.cleanedMask
        val skipRows = crosswalkRows(crosswalkResult.crosswalkMask)
        val roiTop = (workingMask.height * ROI_TOP_RATIO).toInt()

        val left = analyzeSide(
            workingMask, skipRows, frame, channels, Side.LEFT, leftHistory, roiTop
        )
        val right = analyzeSide(
            workingMask, skipRows, frame, channels, Side.RIGHT, rightHistory, roiTop
        )
        val labels = buildLabels(workingMask, lastCrosswalkMask.takeIf { crosswalkInfo != null }, left, right)

        return LaneClassification(
            left.model,
            right.model,
            ClassifiedLaneMask(mask.width, mask.height, labels),
            crosswalkInfo
        )
    }

    private fun crosswalkRows(crosswalkMask: PixelMask): BooleanArray =
        BooleanArray(crosswalkMask.height) { y ->
            val rowStart = y * crosswalkMask.width
            (rowStart until rowStart + crosswalkMask.width)
                .any { crosswalkMask.pixels[it].toInt() != 0 }
        }

    private fun updateCrosswalkHistory(detected: CrosswalkInfo?): CrosswalkInfo? {
        crosswalkHistory.addLast(detected != null)
        if (crosswalkHistory.size > CROSSWALK_HISTORY_SIZE) crosswalkHistory.removeFirst()
        if (detected != null) lastCrosswalkInfo = detected
        return if (crosswalkHistory.count { it } >= CROSSWALK_CONFIRMATIONS) lastCrosswalkInfo else null
    }

    // ---------------------------------------------------------------------
    // Analiza jednej strony
    // ---------------------------------------------------------------------

    private fun analyzeSide(
        mask: PixelMask,
        skipRows: BooleanArray,
        frame: Mat,
        channels: Int,
        side: Side,
        history: ClassificationHistory,
        roiTop: Int
    ): SideResult {
        val track = findTrack(mask, side, roiTop)
        if (track == null) {
            history.update(LineType.UNKNOWN)
            return SideResult.EMPTY
        }

        val scaleX = frame.cols().toFloat() / mask.width
        val scaleY = frame.rows().toFloat() / mask.height

        val minY = track.minOf { it.y }
        val maxY = track.maxOf { it.y }
        val minSpan = max(MIN_FIT_SPAN_FLOOR, mask.height * MIN_FIT_SPAN_RATIO)
        val curve = if (maxY - minY >= minSpan) fitCurve(track, minY, maxY) else null

        val rawType = curve?.let {
            classifyByColor(mask, skipRows, frame, channels, it, minY, maxY, scaleX, scaleY)
        } ?: LineType.UNKNOWN
        val stableType = history.update(rawType)

        val segments = curve?.let { curveSegments(it, minY, maxY, scaleX, scaleY) }
            ?: run {
                val top = track.minByOrNull { it.y }!!
                val bottom = track.maxByOrNull { it.y }!!
                listOf(
                    LineSegment(
                        LanePoint(top.x * scaleX, top.y * scaleY),
                        LanePoint(bottom.x * scaleX, bottom.y * scaleY)
                    )
                )
            }

        return SideResult(LineModel(stableType, segments), curve, minY, maxY)
    }

    // ---------------------------------------------------------------------
    // ROI i podział na strony
    // ---------------------------------------------------------------------

    private fun splitIndex(mask: PixelMask): Int = ceil(mask.width / 2f).toInt()

    /** Zakres kolumn ROI dla wiersza [y] po danej stronie środka (może być pusty). */
    private fun roiRange(mask: PixelMask, y: Int, side: Side): IntRange {
        val verticalPosition = y.toFloat() / (mask.height - 1).coerceAtLeast(1)
        val halfWidth = MIN_ROI_HALF_WIDTH +
            (MAX_ROI_HALF_WIDTH - MIN_ROI_HALF_WIDTH) * verticalPosition
        val center = mask.width / 2f
        var minX = (center - halfWidth * mask.width).toInt().coerceAtLeast(0)
        var maxX = (center + halfWidth * mask.width).toInt().coerceAtMost(mask.width - 1)
        val split = splitIndex(mask)
        if (side == Side.LEFT) maxX = min(maxX, split - 1) else minX = max(minX, split)
        return minX..maxX
    }

    // ---------------------------------------------------------------------
    // Śledzenie linii okienkiem (od dołu do góry) - lokalizacja na masce
    // ---------------------------------------------------------------------

    private fun findTrack(mask: PixelMask, side: Side, roiTop: Int): List<LanePoint>? {
        val tol = (mask.width * TRACK_TOL_RATIO).toInt().coerceAtLeast(MIN_TRACK_TOL)
        var y = mask.height - 1
        var attempts = 0
        while (y >= roiTop && attempts < MAX_SEED_ATTEMPTS) {
            val seedX = nearestRunCenter(mask, y, side)
            if (seedX == null) {
                y--
                continue
            }
            attempts++
            val track = trackLine(mask, side, roiTop, y, seedX, tol)
            if (track.size >= MIN_TRACK_POINTS) return track
            y -= SEED_RETRY_STEP
        }
        return null
    }

    /** Środek ciągłego fragmentu pikseli najbliższego środka kadru w wierszu [y]. */
    private fun nearestRunCenter(mask: PixelMask, y: Int, side: Side): Float? {
        val range = roiRange(mask, y, side)
        val progression: IntProgression = if (side == Side.LEFT) range.reversed() else range
        var sum = 0
        var count = 0
        for (x in progression) {
            val on = mask.pixels[y * mask.width + x].toInt() != 0
            if (on) {
                sum += x
                count++
            } else if (count > 0) {
                break
            }
        }
        return if (count > 0) sum.toFloat() / count else null
    }

    private fun trackLine(
        mask: PixelMask,
        side: Side,
        roiTop: Int,
        seedY: Int,
        seedX: Float,
        tol: Int
    ): List<LanePoint> {
        val points = ArrayList<LanePoint>()
        points += LanePoint(seedX, seedY.toFloat())

        var lastX = seedX
        var lastY = seedY
        var slope = 0f // przesunięcie x na wiersz w górę obrazu
        var missed = 0
        val maxMissed = (mask.height * MAX_TRACK_GAP_RATIO).toInt().coerceAtLeast(4)

        for (y in seedY - 1 downTo roiTop) {
            val dy = lastY - y
            val predicted = lastX + slope * dy
            val range = roiRange(mask, y, side)
            val from = max(range.first, (predicted - tol).roundToInt())
            val to = min(range.last, (predicted + tol).roundToInt())

            var sum = 0
            var count = 0
            for (x in from..to) {
                if (mask.pixels[y * mask.width + x].toInt() != 0) {
                    sum += x
                    count++
                }
            }

            if (count > 0) {
                val meanX = sum.toFloat() / count
                val measured = (meanX - lastX) / dy
                slope = (SLOPE_SMOOTHING * slope + (1f - SLOPE_SMOOTHING) * measured)
                    .coerceIn(-MAX_SLOPE, MAX_SLOPE)
                lastX = meanX
                lastY = y
                missed = 0
                points += LanePoint(meanX, y.toFloat())
            } else {
                missed++
                if (missed > maxMissed) break
            }
        }
        return points
    }

    // ---------------------------------------------------------------------
    // Klasyfikacja ciągłości na podstawie KOLORU wzdłuż dopasowanej krzywej
    // ---------------------------------------------------------------------

    private fun classifyByColor(
        mask: PixelMask,
        skipRows: BooleanArray,
        frame: Mat,
        channels: Int,
        curve: Curve,
        minY: Float,
        maxY: Float,
        scaleX: Float,
        scaleY: Float
    ): LineType {
        val horizonY = mask.height * HORIZON_RATIO
        val bottomY = (mask.height - 1).toFloat()

        // Okno: od "dość blisko, żeby widać kreski" do końca śladu (bez ekstrapolacji
        // krzywej), z pominięciem dołu kadru (maska auta).
        val y0 = max(minY, mask.height * CLASSIFY_TOP_RATIO).toInt()
        val y1 = min(maxY.toInt(), (mask.height * (1f - BOTTOM_MARGIN_RATIO)).toInt())
            .coerceAtMost(mask.height - 1)
        if (y1 - y0 + 1 < mask.height * MIN_REGION_RATIO) return LineType.UNKNOWN

        // 1) Profil kontrastu: dla każdego wiersza (jasność kreski - jasność asfaltu)
        val n = y1 - y0 + 1
        val contrast = FloatArray(n)
        val valid = BooleanArray(n)
        var validCount = 0
        var eligibleRows = 0
        for (y in y0..y1) {
            if (skipRows[y]) continue
            eligibleRows++
            val c = rowContrast(mask, frame, channels, curve, y, scaleX, scaleY)
            if (c != null) {
                contrast[y - y0] = c
                valid[y - y0] = true
                validCount++
            }
        }
        if (eligibleRows == 0 || validCount < eligibleRows * MIN_VALID_ROWS_RATIO) {
            return LineType.UNKNOWN
        }

        // wiersze bez danych: przenieś ostatnią znaną wartość
        val firstValid = valid.indexOfFirst { it }
        if (firstValid < 0) return LineType.UNKNOWN
        var last = contrast[firstValid]
        for (i in 0 until n) {
            if (valid[i]) last = contrast[i] else contrast[i] = last
        }

        // 2) Wygładzenie (mediana 3) - żeby szum nie robił mikroprzerw
        val smooth = median3(contrast)

        // 3) Próg adaptacyjny: czy w profilu widać dwa poziomy (kreska / asfalt)?
        val sorted = smooth.sortedArray()
        val hi = percentile(sorted, 0.9f)
        val lo = percentile(sorted, 0.1f)
        if (hi < MIN_LINE_CONTRAST) return LineType.UNKNOWN // brak wyraźnej, jasnej linii

        val bimodal = (hi - lo) > MIN_DASH_SEPARATION * hi
        val threshold = if (bimodal) lo + (hi - lo) * 0.5f else hi * 0.5f
        val occupied = BooleanArray(n) { smooth[it] > threshold }

        val first = occupied.indexOfFirst { it }
        val last2 = occupied.indexOfLast { it }
        if (first < 0) return LineType.UNKNOWN

        // 4) Przerwy w jednostkach "drogi" (wagi perspektywy), nie w surowych pikselach
        val minGapW = max(3f, mask.height * MIN_GAP_RATIO)
        var gaps = 0
        var gapW = 0f
        var totalW = 0f
        var coveredW = 0f
        for (i in first..last2) {
            val w = rowWeight(y0 + i, horizonY, bottomY)
            totalW += w
            if (occupied[i]) {
                if (gapW >= minGapW) gaps++
                gapW = 0f
                coveredW += w
            } else {
                gapW += w
            }
        }

        // przerwa tuż pod ostatnią kreską (blisko kamery) też jest dowodem na DASHED;
        // przerwy przy górnej krawędzi okna pomijamy - tam rozdzielczość jest za mała
        var bottomGapW = 0f
        for (i in n - 1 downTo last2 + 1) bottomGapW += rowWeight(y0 + i, horizonY, bottomY)
        if (bottomGapW >= minGapW) gaps++

        val coverage = coveredW / totalW
        val span = (last2 - first + 1).toFloat() / mask.height

        return when {
            bimodal && gaps >= 1 && coverage < DASHED_MAX_COVERAGE -> LineType.DASHED
            gaps == 0 && coverage > CONTINUOUS_MIN_COVERAGE && span >= CONTINUOUS_MIN_SPAN ->
                LineType.CONTINUOUS
            else -> LineType.UNKNOWN
        }
    }

    /** Długość odcinka drogi przypadająca na wiersz, względem wiersza dolnego. */
    private fun rowWeight(y: Int, horizonY: Float, bottomY: Float): Float {
        val d = (y - horizonY).coerceAtLeast(1f)
        val w = (bottomY - horizonY) / d
        return (w * w).coerceAtMost(MAX_ROW_WEIGHT)
    }

    /**
     * Kontrast w wierszu maski [y]: jasność kreski minus jasność asfaltu obok.
     * - szerokość kreski bierzemy z maski (ciągły fragment przy krzywej),
     * - jasność kreski = max z lokalnej średniej 3 pikseli w tym fragmencie klatki,
     * - tło = mediana pikseli tuż poza maską po lewej / prawej, wybieramy ciemniejszą
     *   stronę (asfalt jest ciemniejszy niż farba, a po drugiej stronie może być
     *   inna linia).
     * Zwraca null, gdy w tym wierszu nie ma piksela maski przy krzywej.
     */
    private fun rowContrast(
        mask: PixelMask,
        frame: Mat,
        channels: Int,
        curve: Curve,
        y: Int,
        scaleX: Float,
        scaleY: Float
    ): Float? {
        val rowOffset = y * mask.width
        val cxMask = curve.x(curve.normalize(y.toFloat())).roundToInt()
        val searchTol = (mask.width * OCCUPANCY_TOL_RATIO).toInt().coerceAtLeast(2)
        val maxRun = (mask.width * MAX_RUN_RATIO).toInt().coerceAtLeast(4)

        // piksel maski najbliższy krzywej
        var seed = -1
        for (d in 0..searchTol) {
            val l = cxMask - d
            val r = cxMask + d
            if (l in 0 until mask.width && mask.pixels[rowOffset + l].toInt() != 0) { seed = l; break }
            if (r in 0 until mask.width && mask.pixels[rowOffset + r].toInt() != 0) { seed = r; break }
        }
        if (seed < 0) return null

        // zasięg linii w masce (z limitem, żeby nie zlać się z sąsiednią linią)
        var xl = seed
        var steps = 0
        while (xl > 0 && steps < maxRun && mask.pixels[rowOffset + xl - 1].toInt() != 0) { xl--; steps++ }
        var xr = seed
        steps = 0
        while (xr < mask.width - 1 && steps < maxRun &&
            mask.pixels[rowOffset + xr + 1].toInt() != 0) { xr++; steps++ }

        // przejście do współrzędnych klatki
        val fy = ((y + 0.5f) * scaleY).toInt().coerceIn(0, frame.rows() - 1)
        val lineL = (xl * scaleX).toInt().coerceIn(0, frame.cols() - 1)
        val lineR = (((xr + 1) * scaleX).toInt() - 1).coerceIn(lineL, frame.cols() - 1)
        val margin = max(2, (mask.width * BG_MARGIN_RATIO * scaleX).toInt())
        val bgWidth = max(4, (mask.width * BG_WIDTH_RATIO * scaleX).toInt())

        val x0 = (lineL - margin - bgWidth).coerceAtLeast(0)
        val x1 = (lineR + margin + bgWidth).coerceAtMost(frame.cols() - 1)
        if (x1 - x0 < 2) return null

        // jeden odczyt JNI na wiersz
        val len = x1 - x0 + 1
        val buf = ByteArray(len * channels)
        frame.get(fy, x0, buf)
        val f = IntArray(len) { feature(buf, it, channels) }

        // jasność kreski
        var lineValue = 0f
        val relL = lineL - x0
        val relR = lineR - x0
        for (i in relL..relR) {
            val a = f[(i - 1).coerceAtLeast(0)]
            val b = f[i]
            val c = f[(i + 1).coerceAtMost(len - 1)]
            lineValue = max(lineValue, (a + b + c) / 3f)
        }

        // jasność tła (ciemniejsza strona)
        var background = Float.MAX_VALUE
        val leftEnd = relL - margin - 1
        if (leftEnd >= 0) background = min(background, median(f, 0, leftEnd))
        val rightStart = relR + margin + 1
        if (rightStart <= len - 1) background = min(background, median(f, rightStart, len - 1))
        if (background == Float.MAX_VALUE) return null

        return lineValue - background
    }

    /**
     * Cecha piksela do wykrywania farby: max(R, G). Biała farba ma wysokie R i G,
     * żółta też (wysokie R i G, niskie B), a asfalt jest niski we wszystkich.
     * Dla obrazu jednokanałowego po prostu jasność.
     */
    private fun feature(buf: ByteArray, index: Int, channels: Int): Int {
        val base = index * channels
        if (channels < 3) return buf[base].toInt() and 0xFF
        val r = buf[base + redIndex].toInt() and 0xFF
        val g = buf[base + 1].toInt() and 0xFF
        return max(r, g)
    }

    private fun median(values: IntArray, from: Int, to: Int): Float {
        val copy = values.copyOfRange(from, to + 1)
        copy.sort()
        return copy[copy.size / 2].toFloat()
    }

    private fun median3(src: FloatArray): FloatArray {
        if (src.size < 3) return src
        val out = src.copyOf()
        for (i in 1 until src.size - 1) {
            val a = src[i - 1]
            val b = src[i]
            val c = src[i + 1]
            out[i] = max(min(a, b), min(max(a, b), c))
        }
        return out
    }

    private fun percentile(sorted: FloatArray, p: Float): Float =
        sorted[((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)]

    // ---------------------------------------------------------------------
    // Dopasowanie krzywej x(y) = a t^2 + b t + c
    // ---------------------------------------------------------------------

    private fun fitCurve(points: List<LanePoint>, minY: Float, maxY: Float): Curve? {
        val centerY = (minY + maxY) / 2f
        val halfSpan = (maxY - minY) / 2f
        if (halfSpan <= 0f) return null

        val sums = DoubleArray(5)
        val right = DoubleArray(3)
        for (point in points) {
            val t = (point.y - centerY) / halfSpan
            val t2 = t * t
            val t3 = t2 * t
            val t4 = t2 * t2
            sums[0] = sums[0] + 1.0
            sums[1] = sums[1] + t
            sums[2] = sums[2] + t2
            sums[3] = sums[3] + t3
            sums[4] = sums[4] + t4
            right[0] = right[0] + point.x
            right[1] = right[1] + point.x * t
            right[2] = right[2] + point.x * t2
        }

        val solution = solve(
            arrayOf(
                doubleArrayOf(sums[0], sums[1], sums[2]),
                doubleArrayOf(sums[1], sums[2], sums[3]),
                doubleArrayOf(sums[2], sums[3], sums[4])
            ),
            right
        ) ?: return null
        return Curve(
            a = solution[2].toFloat(),
            b = solution[1].toFloat(),
            c = solution[0].toFloat(),
            centerY = centerY,
            halfSpan = halfSpan
        )
    }

    private fun solve(matrix: Array<DoubleArray>, values: DoubleArray): DoubleArray? {
        for (pivot in 0..2) {
            var bestRow = pivot
            for (row in pivot + 1..2) {
                if (abs(matrix[row][pivot]) > abs(matrix[bestRow][pivot])) {
                    bestRow = row
                }
            }
            if (abs(matrix[bestRow][pivot]) < 1e-9) return null
            val tempRow = matrix[pivot]
            matrix[pivot] = matrix[bestRow]
            matrix[bestRow] = tempRow
            val tempValue = values[pivot]
            values[pivot] = values[bestRow]
            values[bestRow] = tempValue

            val divisor = matrix[pivot][pivot]
            for (column in pivot..2) matrix[pivot][column] /= divisor
            values[pivot] /= divisor
            for (row in 0..2) {
                if (row == pivot) continue
                val factor = matrix[row][pivot]
                for (column in pivot..2) matrix[row][column] -= factor * matrix[pivot][column]
                values[row] -= factor * values[pivot]
            }
        }
        return values
    }

    private fun curveSegments(
        curve: Curve,
        minY: Float,
        maxY: Float,
        scaleX: Float,
        scaleY: Float
    ): List<LineSegment> {
        val steps = ((maxY - minY) / CURVE_SEGMENT_HEIGHT).toInt().coerceAtLeast(1)
        return (0 until steps).map { index ->
            val firstY = minY + (maxY - minY) * index / steps
            val secondY = minY + (maxY - minY) * (index + 1) / steps
            LineSegment(
                LanePoint(curve.x(curve.normalize(firstY)) * scaleX, firstY * scaleY),
                LanePoint(curve.x(curve.normalize(secondY)) * scaleX, secondY * scaleY)
            )
        }
    }

    // ---------------------------------------------------------------------
    // Maska etykiet: tylko piksele w tubie wokół krzywej dostają typ linii
    // ---------------------------------------------------------------------

    private fun buildLabels(
        mask: PixelMask,
        crosswalkMask: PixelMask?,
        left: SideResult,
        right: SideResult
    ): ByteArray {
        val labels = ByteArray(mask.pixels.size)
        crosswalkMask?.let { crossingMask ->
            for (index in crossingMask.pixels.indices) {
                if (crossingMask.pixels[index].toInt() != 0) {
                    labels[index] = ClassifiedLaneMask.CROSSING
                }
            }
        }
        val split = splitIndex(mask)
        val tol = (mask.width * LABEL_TOL_RATIO).toInt().coerceAtLeast(3)
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                val index = y * mask.width + x
                if (mask.pixels[index].toInt() == 0) continue
                val side = if (x < split) left else right
                labels[index] = labelFor(side, x, y, tol)
            }
        }
        return labels
    }

    private fun labelFor(side: SideResult, x: Int, y: Int, tol: Int): Byte {
        val curve = side.curve ?: return ClassifiedLaneMask.UNKNOWN
        val yf = y.toFloat()
        if (yf < side.minY || yf > side.maxY) return ClassifiedLaneMask.UNKNOWN
        val cx = curve.x(curve.normalize(yf))
        if (abs(x - cx) > tol) return ClassifiedLaneMask.UNKNOWN
        return when (side.model?.type) {
            LineType.CONTINUOUS, LineType.SOLID -> ClassifiedLaneMask.CONTINUOUS
            LineType.DASHED -> ClassifiedLaneMask.DASHED
            else -> ClassifiedLaneMask.UNKNOWN
        }
    }

    // ---------------------------------------------------------------------
    // Typy pomocnicze
    // ---------------------------------------------------------------------

    private enum class Side { LEFT, RIGHT }

    private class SideResult(
        val model: LineModel?,
        val curve: Curve?,
        val minY: Float,
        val maxY: Float
    ) {
        companion object {
            val EMPTY = SideResult(null, null, 0f, 0f)
        }
    }

    /**
     * Wygładzanie w czasie. DASHED jest silnym dowodem (ciągła linia sama nie
     * wytworzy przerwy), więc wystarczą 2 z 5 klatek. CONTINUOUS wymaga
     * wyraźnej większości i braku jakiejkolwiek klatki DASHED w oknie.
     * Remis / niepewność -> UNKNOWN.
     */
    private class ClassificationHistory {
        private val values = ArrayDeque<LineType>()

        fun update(type: LineType): LineType {
            values.addLast(type)
            if (values.size > HISTORY_SIZE) values.removeFirst()
            val dashed = values.count { it == LineType.DASHED }
            val continuous = values.count { it == LineType.CONTINUOUS }
            return when {
                dashed >= DASHED_FRAMES -> LineType.DASHED
                dashed == 0 && continuous >= CONTINUOUS_FRAMES -> LineType.CONTINUOUS
                else -> LineType.UNKNOWN
            }
        }
    }

    private data class Curve(
        val a: Float,
        val b: Float,
        val c: Float,
        val centerY: Float,
        val halfSpan: Float
    ) {
        fun normalize(y: Float): Float = (y - centerY) / halfSpan
        fun x(t: Float): Float = a * t * t + b * t + c
    }

    private companion object {
        // ROI
        const val ROI_TOP_RATIO = 0.35f
        const val MIN_ROI_HALF_WIDTH = 0.30f
        const val MAX_ROI_HALF_WIDTH = 0.49f

        // Tracker okienkowy (na masce)
        const val TRACK_TOL_RATIO = 0.02f
        const val MIN_TRACK_TOL = 3
        const val MIN_TRACK_POINTS = 15
        const val MAX_SEED_ATTEMPTS = 8
        const val SEED_RETRY_STEP = 4
        const val MAX_TRACK_GAP_RATIO = 0.25f
        const val SLOPE_SMOOTHING = 0.6f
        const val MAX_SLOPE = 1.5f

        // Dopasowanie krzywej
        const val MIN_FIT_SPAN_RATIO = 0.12f
        const val MIN_FIT_SPAN_FLOOR = 12f
        const val CURVE_SEGMENT_HEIGHT = 12f

        // Klasyfikacja po kolorze: okno i perspektywa
        const val CLASSIFY_TOP_RATIO = 0.55f   // wyżej kreski są za małe, żeby rozróżnić przerwy
        const val BOTTOM_MARGIN_RATIO = 0.08f  // pomijamy maskę auta na dole kadru
        const val HORIZON_RATIO = 0.42f        // ustaw wg swojej kamery / punktu zbiegu
        const val MAX_ROW_WEIGHT = 16f
        const val MIN_REGION_RATIO = 0.15f

        // Klasyfikacja po kolorze: pomiar kontrastu
        const val OCCUPANCY_TOL_RATIO = 0.012f // jak daleko od krzywej szukamy piksela maski
        const val MAX_RUN_RATIO = 0.04f        // maks. szerokość linii w masce (ułamek szerokości)
        const val BG_MARGIN_RATIO = 0.006f     // odstęp tła od krawędzi maski
        const val BG_WIDTH_RATIO = 0.02f       // szerokość próbkowanego pasa tła
        const val MIN_VALID_ROWS_RATIO = 0.7f  // min. odsetek wierszy z danymi
        const val MIN_LINE_CONTRAST = 25f      // poniżej: farba za słabo widoczna -> UNKNOWN (skala 0-255)
        const val MIN_DASH_SEPARATION = 0.35f  // min. różnica kreska/przerwa jako ułamek kontrastu kreski

        // Klasyfikacja po kolorze: decyzja
        const val MIN_GAP_RATIO = 0.025f
        const val DASHED_MAX_COVERAGE = 0.90f
        const val CONTINUOUS_MIN_COVERAGE = 0.92f
        const val CONTINUOUS_MIN_SPAN = 0.25f

        // Maska etykiet
        const val LABEL_TOL_RATIO = 0.03f

        // Historia
        const val HISTORY_SIZE = 5
        const val DASHED_FRAMES = 2
        const val CONTINUOUS_FRAMES = 4
        const val CROSSWALK_HISTORY_SIZE = 4
        const val CROSSWALK_CONFIRMATIONS = 2
    }
}