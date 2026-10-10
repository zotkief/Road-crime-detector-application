package com.example.roadcrimedetector.processing.lanes

import android.content.Context
import org.opencv.core.Mat

class LaneDetector(context: Context) : AutoCloseable {
    private val inference = TwinLiteInference(context)

    @Synchronized
    fun detect(frame: Mat): PixelMask {
        check(!frame.empty()) { "Cannot detect lanes in an empty frame" }
        return inference.infer(frame)
    }

    override fun close() {
        inference.close()
    }
}
