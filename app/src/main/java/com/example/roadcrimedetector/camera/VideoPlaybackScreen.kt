package com.example.roadcrimedetector.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.example.roadcrimedetector.processing.FrameProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

@Composable
fun VideoPlaybackScreen(targetFps: Int) {
    val context = LocalContext.current
    val processor = remember { FrameProcessor(context) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        val videoSource = BufferedVideoSource(context, "input.mp4", targetFps)
        launch(Dispatchers.IO) {
            videoSource.prefetchFrames()
        }

        val playStart = System.currentTimeMillis()
        var frameIndex = 0L

        while (isActive) {
            val frame = videoSource.nextFrame() ?: break
            val processed = withContext(Dispatchers.Default) { processor.process(frame) }
            processedBitmap = processed.bitmap

            val targetTime = playStart + frameIndex * videoSource.frameIntervalMs
            val waitTime = targetTime - System.currentTimeMillis()
            if (waitTime > 0) {
                delay(waitTime)
            }
            frameIndex++
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        processedBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Video playback feed",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private class BufferedVideoSource(
    context: Context,
    assetName: String,
    targetFps: Int,
    private val bufferSize: Int = 6
) {
    private val retriever = MediaMetadataRetriever()
    private val videoFile = copyAssetToCache(context, assetName)
    private val frameQueue = ArrayDeque<Bitmap>()
    private val queueMutex = Mutex()
    private var nextTimeMs = 0L
    private var ended = false

    val durationMs: Long
    val frameIntervalMs: Long

    init {
        retriever.setDataSource(videoFile.absolutePath)
        durationMs = getVideoDurationMs(videoFile.absolutePath)
        val fps = getVideoFrameRate(videoFile.absolutePath) ?: targetFps.toFloat()
        frameIntervalMs = (1000f / fps).toLong().coerceAtLeast(16L)
    }

    suspend fun prefetchFrames() {
        while (true) {
            val shouldBuffer = queueMutex.withLock { frameQueue.size < bufferSize }
            if (!shouldBuffer) {
                delay(10)
                continue
            }

            if (nextTimeMs >= durationMs) {
                ended = true
                break
            }

            val frame = retriever.getFrameAtTime(nextTimeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            nextTimeMs += frameIntervalMs

            if (frame != null) {
                queueMutex.withLock {
                    frameQueue.addLast(frame)
                }
            }
        }

        retriever.release()
    }

    suspend fun nextFrame(): Bitmap? {
        while (true) {
            queueMutex.withLock {
                if (frameQueue.isNotEmpty()) {
                    return frameQueue.removeFirst()
                }
                if (ended) {
                    return null
                }
            }
            delay(10)
        }
    }
}

private fun copyAssetToCache(context: Context, assetName: String): File {
    val outputFile = File(context.cacheDir, assetName)
    if (!outputFile.exists()) {
        context.assets.open(assetName).use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
    return outputFile
}

private fun getVideoDurationMs(path: String): Long {
    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
    } finally {
        retriever.release()
    }
}

private fun getVideoFrameRate(path: String): Float? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(path)
        for (track in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("video/")) {
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    return format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    } finally {
        extractor.release()
    }
}
