package com.example.roadcrimedetector.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import java.io.File
import java.util.concurrent.Executor

class RollingVideoRecorder(
    private val context: Context,
    private val executor: Executor
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val segments = ArrayDeque<File>()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var running = false

    fun start(videoCapture: VideoCapture<Recorder>) {
        synchronized(this) {
            if (running) return
            this.videoCapture = videoCapture
            running = true
            startSegment()
        }
    }

    fun storedSegments(): List<File> = synchronized(this) {
        segments.filter { it.exists() }
    }

    private fun startSegment() {
        val capture = videoCapture ?: return
        val file = File(context.cacheDir, "rolling_${System.currentTimeMillis()}.mp4")
        segments.addLast(file)
        while (segments.size > MAX_SEGMENTS) {
            segments.removeFirst().delete()
        }

        recording = capture.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .withAudioEnabled()
            .start(executor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    synchronized(this) {
                        if (running) startSegment()
                    }
                }
            }
        handler.postDelayed({ rotateSegment() }, SEGMENT_DURATION_MILLIS)
    }

    private fun rotateSegment() {
        synchronized(this) {
            if (!running) return
            recording?.stop()
            recording = null
        }
    }

    override fun close() {
        synchronized(this) {
            running = false
            handler.removeCallbacksAndMessages(null)
            recording?.stop()
            recording = null
            videoCapture = null
        }
    }

    companion object {
        private const val SEGMENT_DURATION_MILLIS = 5_000L
        private const val MAX_SEGMENTS = 6
    }
}