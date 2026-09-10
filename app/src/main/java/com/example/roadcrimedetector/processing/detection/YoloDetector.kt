package com.example.roadcrimedetector.processing.detection

import android.content.Context
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer

fun copyModelIfNeeded(context: Context): String {
    val file = File(context.filesDir, "model.onnx")
    if (!file.exists()) {
        context.assets.open("model.onnx").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
    }
    return file.absolutePath
}

class YoloDetector(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val parser = YoloParser()
    private val resizedMat = Mat()
    private val pixelBuffer = ByteArray(640 * 640 * 3)
    private val floatData = FloatArray(3 * 640 * 640)

    init {
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        session = env.createSession(copyModelIfNeeded(context), options)
        inputName = session.inputNames.first()
    }

    fun detect(frame: Mat): List<YoloDetection> {
        Imgproc.resize(frame, resizedMat, Size(640.0, 640.0))
        Imgproc.cvtColor(resizedMat, resizedMat, Imgproc.COLOR_BGR2RGB)
        resizedMat.get(0, 0, pixelBuffer)

        val plane = 640 * 640
        var pixel = 0
        for (index in 0 until plane) {
            floatData[index] = (pixelBuffer[pixel].toInt() and 0xFF) / 255f
            floatData[plane + index] = (pixelBuffer[pixel + 1].toInt() and 0xFF) / 255f
            floatData[plane * 2 + index] = (pixelBuffer[pixel + 2].toInt() and 0xFF) / 255f
            pixel += 3
        }

        OnnxTensor.createTensor(env, FloatBuffer.wrap(floatData), longArrayOf(1, 3, 640, 640)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val output = results[0].value as Array<Array<FloatArray>>
                return parser.parse(output, frame.width(), frame.height())
            }
        }
    }

    override fun close() {
        resizedMat.release()
        session.close()
    }
}
