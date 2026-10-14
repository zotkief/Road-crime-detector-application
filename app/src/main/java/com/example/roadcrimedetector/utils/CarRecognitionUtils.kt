package com.example.roadcrimedetector.utils

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("UNUSED_PARAMETER")
suspend fun identifyCar(image: Bitmap): Pair<String, String> = withContext(Dispatchers.IO) {
    "Toyota" to "WA 12345"
}
