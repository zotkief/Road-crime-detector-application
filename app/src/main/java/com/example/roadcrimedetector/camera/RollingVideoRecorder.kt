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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.max

class RollingVideoRecorder(
    private val context: Context,
    private val executor: Executor
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val rollingDirectory = File(context.filesDir, "rolling-video").apply { mkdirs() }
    private val segments = ArrayDeque(
        rollingDirectory.listFiles()
            ?.filter(File::isFile)
            ?.sortedBy(::segmentTimestamp)
            .orEmpty()
    )
    private val activeIncidents = mutableMapOf<String, Long>()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var activeSegment: File? = null
    private var pendingSnapshot: ((List<File>) -> Unit)? = null
    private var running = false

    private val rotateRunnable = Runnable { rotateSegment() }

    fun start(videoCapture: VideoCapture<Recorder>) {
        synchronized(this) {
            if (running) return
            this.videoCapture = videoCapture
            running = true
            startSegment()
        }
    }

    fun storedSegments(): List<File> = synchronized(this) {
        recentStoredSegments()
    }

    suspend fun snapshotStoredSegments(): List<File> = suspendCancellableCoroutine { continuation ->
        synchronized(this) {
            if (activeSegment == null) {
                continuation.resume(recentStoredSegments())
            } else {
                pendingSnapshot = { files ->
                    if (continuation.isActive) continuation.resume(files)
                }
                handler.removeCallbacks(rotateRunnable)
                recording?.let {
                    recording = null
                    it.stop()
                }
            }
        }
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
        activeSegment = file

        recording = capture.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .withAudioEnabled()
            .start(executor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    synchronized(this) {
                        activeSegment = null
                        if (running) startSegment()
                        val snapshotCallback = pendingSnapshot
                        pendingSnapshot = null
                        snapshotCallback?.invoke(recentStoredSegments())
                    }
                }
            }
        handler.removeCallbacks(rotateRunnable)
        handler.postDelayed(rotateRunnable, SEGMENT_DURATION_MILLIS)
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
        val rollingCutoff = rollingVideoCutoff()
        val oldestRequired = activeIncidents.values.minOrNull()?.minus(BACK_VIDEO_DURATION_MILLIS)
        while (segments.isNotEmpty()) {
            val oldest = segments.firstOrNull() ?: break
            val timestamp = segmentTimestamp(oldest)
            if (timestamp >= rollingCutoff || (oldestRequired != null && timestamp >= oldestRequired)) break
            segments.removeFirst().delete()
        }
    }

    private fun recentStoredSegments(): List<File> {
        val cutoff = rollingVideoCutoff()
        return segments.filter { file ->
            file.exists() && file != activeSegment && segmentTimestamp(file) >= cutoff
        }
    }

    private fun rollingVideoCutoff(): Long =
        System.currentTimeMillis() - ROLLING_VIDEO_DURATION_MILLIS

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
        private const val ROLLING_VIDEO_DURATION_MILLIS = 5 * 60 * 1_000L
        private const val BACK_VIDEO_DURATION_MILLIS = 30_000L
        private const val FORWARD_VIDEO_DURATION_MILLIS = 30_000L
    }
}