package com.example.roadcrimedetector.processing.lanes

import kotlin.math.max
import kotlin.math.min
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Wykrywa przejścia dla pieszych na podstawie maski linii oraz klatki wideo.
 *
 * 1) Poziome belki w masce (stop linie / granice przejścia) są ZAWSZE usuwane z maski linii,
 *    żeby nie powodowały błędów w dalszej klasyfikacji.
 * 2) Wokół belek sprawdzamy w klatce wzorzec naprzemiennych pasów.
 * 3) Jeśli wzorzec potwierdzony: cały obszar (pasy + powiązane belki) trafia do crosswalkMask
 *    (etykieta CROSSING) i jest wycinany z cleanedMask.
 */
class CrosswalkDetector(private val redIndex: Int) {

    /** Obszar przejścia w układzie współrzędnych maski (granice włącznie). */
    data class Crosswalk(val yTop: Int, val yBottom: Int, val xLeft: Int, val xRight: Int)

    data class Result(
        val crosswalks: List<Crosswalk>,
        /** Maska obszarów przejść (255 = CROSSING). */
        val crosswalkMask: PixelMask,
        /** Maska linii bez poziomych belek i bez obszarów przejść. */
        val cleanedMask: PixelMask
    )

    private class Bars(
        val ranges: List<IntRange>,
        val runsByRow: Array<MutableList<IntRange>?>
    )

    fun detect(mask: PixelMask, frame: Mat, channels: Int): Result {
        require(!frame.empty()) { "Cannot detect a crosswalk in an empty frame" }
        require(channels == frame.channels()) { "Frame channel count does not match" }
        require(frame.depth() == CvType.CV_8U) { "Frame must be 8-bit" }
        require(channels == 1 || redIndex in 0 until channels) {
            "Red channel index is outside the frame channels"
        }

        val crossingPixels = ByteArray(mask.width * mask.height)
        val crossingMask = PixelMask(mask.width, mask.height, crossingPixels, LineType.CROSSING)

        val bars = findHorizontalBars(mask)
        if (bars.ranges.isEmpty()) {
            return Result(emptyList(), crossingMask, mask)
        }

        // 1) Zawsze usuń poziome belki (tylko ich piksele, nie całe wiersze).
        val cleaned = mask.pixels.copyOf()
        for (bar in bars.ranges) {
            for (y in bar) {
                val rowOffset = y * mask.width
                bars.runsByRow[y]?.forEach { run ->
                    cleaned.fill(0, rowOffset + run.first, rowOffset + run.last + 1)
                }
            }
        }

        // 2) Wiersze do sprawdzenia: suma okien wokół belek, każdy wiersz liczony raz.
        val scaleX = mask.width.toFloat() / frame.cols()
        val scaleY = frame.rows().toFloat() / mask.height
        val window = (mask.height * SEARCH_WINDOW_RATIO).toInt()
        val needed = BooleanArray(mask.height)
        for (bar in bars.ranges) {
            val start = (bar.first - window).coerceAtLeast(0)
            val end = (bar.last + window).coerceAtMost(mask.height - 1)
            for (y in start..end) needed[y] = true
        }

        val stripeRows = BooleanArray(mask.height)
        val rowSpans = arrayOfNulls<IntRange>(mask.height) // zakres X w układzie maski
        var y = 0
        while (y < mask.height) {
            if (needed[y]) {
                val span = findStripeSpan(frame, channels, (y + 0.5f) * scaleY)
                if (span != null) {
                    stripeRows[y] = true
                    rowSpans[y] = (span.first * scaleX).toInt().coerceIn(0, mask.width - 1)..
                        (span.last * scaleX).toInt().coerceIn(0, mask.width - 1)
                }
            }
            y += ROW_STEP
        }

        // 3) Pasma -> przejścia (każde osobno).
        val minimumHits = (mask.height * MIN_BAND_RATIO).toInt().coerceAtLeast(1)
        val associationMargin = (mask.height * SEARCH_WINDOW_RATIO / 2f).toInt()
        val crosswalks = ArrayList<Crosswalk>()

        for (band in groupRows(stripeRows, MAX_GAP_ROWS)) {
            val linkedBars = bars.ranges.filter { bar ->
                band.first <= bar.last + associationMargin &&
                    band.last >= bar.first - associationMargin
            }
            if (linkedBars.isEmpty()) continue

            val hitCount = band.count { stripeRows[it] }
            val required = if (linkedBars.size >= 2) (minimumHits / 2).coerceAtLeast(1) else minimumHits
            if (hitCount < required) continue


            // Obszar obejmuje pasy oraz powiązane belki (granice przejścia).
            var top = band.first
            var bottom = band.last
            for (bar in linkedBars) {
                top = min(top, bar.first)
                bottom = max(bottom, bar.last)
            }
            top = (top - 1).coerceAtLeast(0)
            bottom = (bottom + 1).coerceAtMost(mask.height - 1)

            val spans = band.mapNotNull { rowSpans[it] }
            if (spans.isEmpty()) continue
            val crosswalk = Crosswalk(
                yTop = top,
                yBottom = bottom,
                xLeft = spans.minOf { it.first },
                xRight = spans.maxOf { it.last }
            )
            crosswalks += crosswalk

            for (row in crosswalk.yTop..crosswalk.yBottom) {
                val from = row * mask.width + crosswalk.xLeft
                val to = row * mask.width + crosswalk.xRight + 1
                crossingPixels.fill(255.toByte(), from, to) // CROSSING
                cleaned.fill(0, from, to)                    // brak linii wewnątrz przejścia
            }
        }

        return Result(
            crosswalks,
            crossingMask,
            PixelMask(mask.width, mask.height, cleaned, mask.lineType)
        )
    }

    private fun findHorizontalBars(mask: PixelMask): Bars {
        val flaggedRows = BooleanArray(mask.height)
        val runsByRow = arrayOfNulls<MutableList<IntRange>>(mask.height)
        val roiTop = (mask.height * ROI_TOP_RATIO).toInt()
        val minimumRun = (mask.width * MIN_HRUN_RATIO).toInt()

        for (y in roiTop until mask.height) {
            val rowOffset = y * mask.width
            var x = 0
            while (x < mask.width) {
                if (mask.pixels[rowOffset + x].toInt() == 0) {
                    x++
                    continue
                }

                val start = x
                while (x < mask.width && mask.pixels[rowOffset + x].toInt() != 0) x++
                if (x - start > minimumRun) {
                    flaggedRows[y] = true
                    val list = runsByRow[y] ?: ArrayList<IntRange>().also { runsByRow[y] = it }
                    list += start until x
                }
            }
        }

        val maximumThickness = (mask.height * MAX_BAR_THICKNESS_RATIO).toInt().coerceAtLeast(1)
        val ranges = groupRows(flaggedRows, MAX_BAR_ROW_GAP)
            .filter { it.last - it.first + 1 <= maximumThickness }
        return Bars(ranges, runsByRow)
    }

    /**
     * Sprawdza, czy w wierszu klatki jest łańcuch naprzemiennych pasów.
     * Zwraca zakres X (w układzie klatki) najdłuższego pasującego łańcucha albo null.
     */
    private fun findStripeSpan(frame: Mat, channels: Int, yMask: Float): IntRange? {
        val y = yMask.toInt().coerceIn(0, frame.rows() - 1)
        val margin = (frame.cols() * ROW_X_MARGIN).toInt()
        val x0 = margin.coerceAtMost(frame.cols() - 1)
        val x1 = (frame.cols() - margin - 1).coerceAtLeast(x0)
        val length = x1 - x0 + 1
        if (length < MIN_STRIPES * 2) return null

        val pixels = ByteArray(length * channels)
        frame.get(y, x0, pixels)
        val values = FloatArray(length) { index ->
            val base = index * channels
            val first = pixels[base].toInt() and 0xFF
            if (channels < 3) {
                first.toFloat()
            } else {
                val red = pixels[base + redIndex].toInt() and 0xFF
                val green = pixels[base + 1].toInt() and 0xFF
                max(red, green).toFloat()
            }
        }

        val smooth = FloatArray(length) { index ->
            val left = values[(index - 1).coerceAtLeast(0)]
            val right = values[(index + 1).coerceAtMost(length - 1)]
            (left + values[index] + right) / 3f
        }

        // Percentyle z histogramu (wartości 0..255) zamiast sortowania.
        val histogram = IntArray(256)
        for (v in smooth) histogram[v.toInt().coerceIn(0, 255)]++
        val low = histogramPercentile(histogram, length, 0.05f)
        val high = histogramPercentile(histogram, length, 0.95f)
        if (high - low < MIN_STRIPE_CONTRAST) return null
        val threshold = (high + low) / 2f

        val minimumWidth = (length * MIN_STRIPE_W_RATIO).toInt().coerceAtLeast(1)
        val maximumWidth = (length * MAX_STRIPE_W_RATIO).toInt().coerceAtLeast(minimumWidth)
        val stripes = ArrayList<IntRange>()
        var index = 0
        while (index < length) {
            if (smooth[index] <= threshold) {
                index++
                continue
            }
            val start = index
            while (index < length && smooth[index] > threshold) index++
            val width = index - start
            if (width in minimumWidth..maximumWidth) stripes += start until index
        }
        if (stripes.size < MIN_STRIPES) return null

        var chainStart = 0
        var chainLength = 1
        var bestStart = 0
        var bestEnd = 0
        var bestLength = 1
        var previousPeriod: Float? = null
        for (stripeIndex in 1 until stripes.size) {
            val previous = stripes[stripeIndex - 1]
            val current = stripes[stripeIndex]
            val previousWidth = previous.last - previous.first + 1
            val currentWidth = current.last - current.first + 1
            val gap = current.first - previous.last - 1
            val narrowWidth = min(previousWidth, currentWidth).toFloat()
            val wideWidth = max(previousWidth, currentWidth).toFloat()
            val currentPeriod = (currentWidth + gap).toFloat()
            val widthAndGapCompatible =
                currentWidth / previousWidth.toFloat() in MIN_WIDTH_RATIO..MAX_WIDTH_RATIO &&
                gap >= MIN_GAP_WIDTH_RATIO * narrowWidth &&
                gap <= MAX_GAP_WIDTH_RATIO * wideWidth
            val periodCompatible = previousPeriod?.let {
                currentPeriod / it in MIN_PERIOD_RATIO..MAX_PERIOD_RATIO
            } ?: true
            val compatible = widthAndGapCompatible && periodCompatible

            if (compatible) {
                chainLength++
            } else {
                chainLength = 1
                chainStart = stripeIndex
            }
            if (chainLength > bestLength) {
                bestLength = chainLength
                bestStart = chainStart
                bestEnd = stripeIndex
            }
            previousPeriod = currentPeriod.takeIf { widthAndGapCompatible }
        }
        if (bestLength < MIN_STRIPES) return null
        return (x0 + stripes[bestStart].first)..(x0 + stripes[bestEnd].last)
    }

    private fun groupRows(rows: BooleanArray, maximumGap: Int): List<IntRange> {
        val groups = ArrayList<IntRange>()
        var first = -1
        var last = -1
        for (y in rows.indices) {
            if (rows[y]) {
                if (first < 0) first = y
                last = y
            } else if (first >= 0 && y - last - 1 > maximumGap) {
                groups += first..last
                first = -1
            }
        }
        if (first >= 0) groups += first..last
        return groups
    }

    private fun histogramPercentile(histogram: IntArray, total: Int, percentile: Float): Float {
        val target = ((total - 1) * percentile).toInt()
        var accumulated = 0
        for (value in histogram.indices) {
            accumulated += histogram[value]
            if (accumulated > target) return value.toFloat()
        }
        return 255f
    }

    private companion object {
        // --- belki ---
        const val ROI_TOP_RATIO = 0.30f              // było 0.35 (wcześniej łapie dalekie przejścia)
        const val MIN_HRUN_RATIO = 0.05f             // było 0.08 (krótsze belki też się liczą)
        const val MAX_BAR_THICKNESS_RATIO = 0.05f    // było 0.025 (grubsze belki nie są odrzucane)
        const val MAX_BAR_ROW_GAP = 3                // było 2

        // --- okno poszukiwań i pasy ---
        const val SEARCH_WINDOW_RATIO = 0.18f        // było 0.12 (większy obszar wokół belki)
        const val ROW_X_MARGIN = 0.03f               // było 0.08 (przejście może dochodzić do krawędzi)
        const val MIN_STRIPE_CONTRAST = 15f          // było 25 (słabsze oświetlenie, wytarta farba)
        const val MIN_STRIPE_W_RATIO = 0.005f        // było 0.008 (dalekie, wąskie pasy)
        const val MAX_STRIPE_W_RATIO = 0.15f         // było 0.12 (bliskie, szerokie pasy)

        // --- zgodność pasów ---
        const val MIN_WIDTH_RATIO = 0.4f             // było 0.55
        const val MAX_WIDTH_RATIO = 2.5f             // było 1.8
        const val MIN_GAP_WIDTH_RATIO = 0.25f        // było 0.4
        const val MAX_GAP_WIDTH_RATIO = 4.5f         // było 3
        const val MIN_PERIOD_RATIO = 0.45f           // było 0.6
        const val MAX_PERIOD_RATIO = 2.2f 
        const val ROW_STEP = 3
        const val MIN_STRIPES = 3

        const val MAX_GAP_ROWS = 2 * ROW_STEP + 2
        const val MIN_BAND_RATIO = 0.01f             // było 0.02 (mniej trafień w pasmie)
    }
}