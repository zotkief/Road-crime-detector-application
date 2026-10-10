package com.example.roadcrimedetector.processing.lanes

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer

internal class TwinLiteInference(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val inputPixels = ByteArray(INPUT_WIDTH * INPUT_HEIGHT * CHANNELS)
    private val inputData = FloatArray(INPUT_WIDTH * INPUT_HEIGHT * CHANNELS)
    private val resized = Mat()
    private val rgb = Mat()

    init {
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = environment.createSession(copyModelIfNeeded(context), options)
        inputName = session.inputNames.first()
        require(session.outputNames.contains(LANE_OUTPUT_NAME)) {
            "TwinLite ONNX model does not provide $LANE_OUTPUT_NAME"
        }
    }

    fun infer(frame: Mat): PixelMask {
        Imgproc.resize(frame, resized, Size(INPUT_WIDTH.toDouble(), INPUT_HEIGHT.toDouble()))
        when (resized.channels()) {
            3 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
            4 -> Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_RGBA2RGB)
            else -> error("Unsupported lane detector input with ${resized.channels()} channels")
        }
        rgb.get(0, 0, inputPixels)
        fillInput()

        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, CHANNELS.toLong(), INPUT_HEIGHT.toLong(), INPUT_WIDTH.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val output = results.get(LANE_OUTPUT_NAME).orElseThrow {
                    IllegalStateException("TwinLite ONNX model output $LANE_OUTPUT_NAME is missing")
                } as OnnxTensor
                val outputInfo = output.info as TensorInfo
                require(outputInfo.shape.contentEquals(OUTPUT_SHAPE)) {
                    "TwinLite ONNX lane output must have shape [1, 2, 360, 640], " +
                        "got ${outputInfo.shape.contentToString()}"
                }
                @Suppress("UNCHECKED_CAST")
                val scores = output.value as Array<Array<Array<FloatArray>>>
                val pixels = ByteArray(INPUT_WIDTH * INPUT_HEIGHT) { pixel ->
                    if (scores[0][1][pixel / INPUT_WIDTH][pixel % INPUT_WIDTH] >
                        scores[0][0][pixel / INPUT_WIDTH][pixel % INPUT_WIDTH]
                    ) 0xFF.toByte() else 0
                }
                return PixelMask(INPUT_WIDTH, INPUT_HEIGHT, pixels, LineType.OTHER)
            }
        }
    }

    private fun fillInput() {
        val planeSize = INPUT_WIDTH * INPUT_HEIGHT
        for (pixel in 0 until planeSize) {
            val source = pixel * CHANNELS
            inputData[pixel] = (inputPixels[source].toInt() and 0xFF) / 255f
            inputData[planeSize + pixel] = (inputPixels[source + 1].toInt() and 0xFF) / 255f
            inputData[planeSize * 2 + pixel] = (inputPixels[source + 2].toInt() and 0xFF) / 255f
        }
    }

    override fun close() {
        resized.release()
        rgb.release()
        session.close()
    }

    private fun copyModelIfNeeded(context: Context): String {
        val modelFile = File(context.filesDir, MODEL_NAME)
        if (!modelFile.exists()) {
            context.assets.open(MODEL_NAME).use { input ->
                modelFile.outputStream().use(input::copyTo)
            }
        }
        return modelFile.absolutePath
    }

    private companion object {
        const val MODEL_NAME = "twinline.onnx"
        const val LANE_OUTPUT_NAME = "serving_default_output_1_output"
        const val INPUT_WIDTH = 640
        const val INPUT_HEIGHT = 360
        const val CHANNELS = 3
        val OUTPUT_SHAPE = longArrayOf(1, 2, INPUT_HEIGHT.toLong(), INPUT_WIDTH.toLong())
    }
}
