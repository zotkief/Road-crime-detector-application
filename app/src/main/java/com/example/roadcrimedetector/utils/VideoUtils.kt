package com.example.roadcrimedetector.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun mergeVideosIntoSingleFile(
    videoFiles: List<File>,
    outputFile: File
): File? = withContext(Dispatchers.IO) {
    val existingFiles = videoFiles.filter(File::exists)
    if (existingFiles.isEmpty()) return@withContext null

    var muxer: MediaMuxer? = null
    try {
        outputFile.parentFile?.mkdirs()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTracks = mutableMapOf<String, Int>()

        existingFiles.forEach { file ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                for (trackIndex in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(trackIndex)
                    val mimeType = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (mimeType !in outputTracks && (mimeType.startsWith("audio/") || mimeType.startsWith("video/"))) {
                        outputTracks[mimeType] = muxer.addTrack(format)
                    }
                }
            } finally {
                extractor.release()
            }
        }

        if (outputTracks.isEmpty()) return@withContext null
        muxer.start()

        var timestampOffsetUs = 0L
        existingFiles.forEach { file ->
            val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            var segmentDurationUs = 0L
            try {
                for (trackIndex in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(trackIndex)
                    val mimeType = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    val outputTrack = outputTracks[mimeType] ?: continue
                    extractor.selectTrack(trackIndex)

                    var buffer = ByteBuffer.allocate(1024 * 1024)
                    val bufferInfo = MediaCodec.BufferInfo()
                    while (true) {
                        val sampleSize = extractor.sampleSize
                        if (sampleSize < 0) break
                        if (sampleSize > buffer.capacity()) {
                            buffer = ByteBuffer.allocate(sampleSize.toInt())
                        }

                        buffer.clear()
                        val bytesRead = extractor.readSampleData(buffer, 0)
                        if (bytesRead < 0) break
                        val sampleTimeUs = extractor.sampleTime
                        segmentDurationUs = maxOf(segmentDurationUs, sampleTimeUs)
                        bufferInfo.set(0, bytesRead, sampleTimeUs + timestampOffsetUs, extractor.sampleFlags)
                        muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                        extractor.advance()
                    }
                    extractor.unselectTrack(trackIndex)
                }
            } finally {
                extractor.release()
            }
            timestampOffsetUs += segmentDurationUs + 1L
        }

        outputFile
    } catch (_: Exception) {
        outputFile.delete()
        null
    } finally {
        muxer?.stopSafely()
        muxer?.release()
    }
}

private fun MediaMuxer.stopSafely() {
    try {
        stop()
    } catch (_: IllegalStateException) {
        // The muxer may not have been started when no usable track was found.
    }
}