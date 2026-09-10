package com.example.roadcrimedetector.utils

import android.graphics.Bitmap
import android.media.Image
import androidx.camera.core.ImageProxy

fun ImageProxy.toBitmapRGBA(): Bitmap {
    val buffer = planes[0].buffer
    buffer.rewind()
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buffer) }
}
