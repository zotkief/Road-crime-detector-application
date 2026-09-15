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
import kotlin.math.max

class RollingVideoRecorder(
    private val context: Context,
    private val executor: Executor
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val segments = ArrayDeque<File>()
    private val activeIncidents = mutableMapOf<String, Long>()
    private val rollingDirectory = File(context.filesDir, "rolling-video").apply { mkdirs() }
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

    fun captureIncident(incidentId: String, onReady: (List<File>) -> Unit) {
        synchronized(this) {
            if (!running) {
                onReady(emptyList())
                return
            }
            val startedAt = System.currentTimeMillis()
            activeIncidents[incidentId] = startedAt
            handler.postDelayed({
                val files = synchronized(this) {
                    val incidentFiles = copyIncidentSegments(incidentId, startedAt)
                    activeIncidents.remove(incidentId)
                    cleanupSegments()
                    incidentFiles
                }
                onReady(files)
            }, FORWARD_VIDEO_DURATION_MILLIS)
        }
    }

    private fun startSegment() {
        val capture = videoCapture ?: return
        val file = File(rollingDirectory, "rolling_${System.currentTimeMillis()}.mp4")
        segments.addLast(file)
        cleanupSegments()

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

    private fun cleanupSegments() {
        val oldestRequired = activeIncidents.values.minOrNull()?.minus(BACK_VIDEO_DURATION_MILLIS)
        while (segments.size > MAX_SEGMENTS) {
            val oldest = segments.firstOrNull() ?: break
            val timestamp = segmentTimestamp(oldest)
            if (oldestRequired != null && timestamp >= oldestRequired) break
            segments.removeFirst().delete()
        }
    }

    private fun copyIncidentSegments(incidentId: String, startedAt: Long): List<File> {
        val destinationDirectory = File(context.filesDir, "incidents/$incidentId/video").apply { mkdirs() }
        val oldestAllowed = startedAt - BACK_VIDEO_DURATION_MILLIS
        val newestAllowed = startedAt + FORWARD_VIDEO_DURATION_MILLIS
        return rollingDirectory.listFiles()
            ?.filter { file ->
                val timestamp = segmentTimestamp(file)
                timestamp in oldestAllowed..newestAllowed
            }
            ?.sortedBy(::segmentTimestamp)
            ?.map { source ->
                val destination = File(destinationDirectory, source.name)
                source.copyTo(destination, overwrite = true)
                destination
            }
            ?: emptyList()
    }

    private fun segmentTimestamp(file: File): Long =
        file.name.removePrefix("rolling_").removeSuffix(".mp4").toLongOrNull() ?: Long.MAX_VALUE

    companion object {
        private const val SEGMENT_DURATION_MILLIS = 5_000L
        private const val MAX_SEGMENTS = 6
        private const val BACK_VIDEO_DURATION_MILLIS = 30_000L
        private const val FORWARD_VIDEO_DURATION_MILLIS = 30_000L
    }
}