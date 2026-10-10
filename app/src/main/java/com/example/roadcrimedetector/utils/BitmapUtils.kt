package com.example.roadcrimedetector.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import androidx.camera.core.ImageProxy

fun ImageProxy.toBitmapRGBA(rotationDegrees: Int = imageInfo.rotationDegrees): Bitmap {
    val buffer = planes[0].buffer
    buffer.rewind()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        copyPixelsFromBuffer(buffer)
    }
    val rotation = ((rotationDegrees % 360) + 360) % 360
    if (rotation == 0) return bitmap

    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        bitmap.recycle()
    }
}
