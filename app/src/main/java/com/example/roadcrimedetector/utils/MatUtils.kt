package com.example.roadcrimedetector.utils

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

fun Bitmap.toBgrMat(): Mat = Mat().also {
    Utils.bitmapToMat(this, it)
    Imgproc.cvtColor(it, it, Imgproc.COLOR_RGBA2BGR)
}

fun Mat.toBitmap(): Bitmap = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(this, it) }
