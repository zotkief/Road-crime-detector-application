package com.example.roadcrimedetector.processing.detection

class YoloParser {
    private val vehicleClasses = setOf(2, 3, 5, 7)

    fun parse(output: Array<Array<FloatArray>>, frameW: Int, frameH: Int): List<YoloDetection> {
        val raw = output[0]
        val candidates = mutableListOf<YoloDetection>()
        val scaleX = frameW.toFloat() / 640f
        val scaleY = frameH.toFloat() / 640f

        for (i in 0 until 8400) {
            var bestClass = -1
            var bestScore = 0f
            for (classIndex in 4 until 84) {
                val score = raw[classIndex][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex - 4
                }
            }
            if (bestScore < 0.15f || bestClass !in vehicleClasses) continue
            candidates.add(
                YoloDetection(
                    x = raw[0][i] * scaleX,
                    y = raw[1][i] * scaleY,
                    w = raw[2][i] * scaleX,
                    h = raw[3][i] * scaleY,
                    confidence = bestScore,
                    classId = bestClass
                )
            )
        }
        return nonMaxSuppression(candidates)
    }

    private fun nonMaxSuppression(list: List<YoloDetection>): List<YoloDetection> {
        val sorted = list.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<YoloDetection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best, it) > 0.45f }
        }
        return result
    }

    private fun iou(a: YoloDetection, b: YoloDetection): Float {
        val x1 = maxOf(a.x1, b.x1)
        val y1 = maxOf(a.y1, b.y1)
        val x2 = minOf(a.x2, b.x2)
        val y2 = minOf(a.y2, b.y2)
        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) +
            (b.x2 - b.x1) * (b.y2 - b.y1) - intersection + 1e-6f
        return intersection / union
    }
}
