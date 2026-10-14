package com.example.roadcrimedetector.processing.tracking

/**
 * Algorytm węgierski (Kuhn-Munkres, wersja z potencjałami) dla macierzy prostokątnych.
 *
 * Znajduje przypisanie jeden-do-jednego o minimalnej sumie kosztów. Dla macierzy
 * rows x cols przypisuje min(rows, cols) par. Pary, których nie chcesz dopuścić,
 * oznacz bardzo dużym kosztem i odrzuć po wyniku (solver najpierw minimalizuje
 * liczbę takich par, a dopiero potem koszt reszty).
 *
 * Złożoność O(min^2 * max). Obliczenia w Double, żeby duże koszty zakazane
 * nie psuły precyzji kosztów rzeczywistych.
 */
object HungarianAlgorithm {

    private const val NON_FINITE_COST = 1e9

    /**
     * @param cost macierz kosztów w układzie wierszowym: cost[row * cols + col]
     * @return tablica długości [rows]: kolumna przypisana do wiersza lub -1
     */
    fun solve(cost: FloatArray, rows: Int, cols: Int): IntArray {
        require(cost.size == rows * cols) { "cost.size must equal rows * cols" }
        val result = IntArray(rows) { -1 }
        if (rows == 0 || cols == 0) return result

        // Algorytm wymaga n <= m (wierszy nie więcej niż kolumn), więc w razie potrzeby transponujemy.
        val transposed = rows > cols
        val n = if (transposed) cols else rows
        val m = if (transposed) rows else cols
        val stride = m + 1

        // Indeksowanie od 1; wiersz i kolumna 0 to wartownicy.
        val a = DoubleArray((n + 1) * stride)
        for (i in 1..n) {
            for (j in 1..m) {
                val original = if (transposed) cost[(j - 1) * cols + (i - 1)] else cost[(i - 1) * cols + (j - 1)]
                a[i * stride + j] = if (original.isFinite()) original.toDouble() else NON_FINITE_COST
            }
        }

        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)   // p[j] = wiersz przypisany do kolumny j
        val way = IntArray(m + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..m) {
                    if (used[j]) continue
                    val current = a[i0 * stride + j] - u[i0] - v[j]
                    if (current < minv[j]) {
                        minv[j] = current
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                for (j in 0..m) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        for (j in 1..m) {
            val i = p[j]
            if (i == 0) continue
            if (transposed) result[j - 1] = i - 1 else result[i - 1] = j - 1
        }
        return result
    }
}