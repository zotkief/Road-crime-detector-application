package com.example.roadcrimedetector.shared

class FrameMemory {
    private val fragments = mutableListOf<FrameFragment>()

    fun storeFragment(data: ByteArray): FrameFragment {
        val fragment = FrameFragment(
            id = "fragment-${currentTimeMillis()}",
            data = data.copyOf(),
            timestamp = currentTimeMillis()
        )

        synchronized(this) {
            fragments.add(fragment)
            if (fragments.size > 12) {
                fragments.removeAt(0)
            }
        }

        return fragment
    }

    fun listFragments(): List<FrameFragment> = synchronized(this) {
        fragments.toList()
    }

    fun clear() {
        synchronized(this) {
            fragments.clear()
        }
    }
}

data class FrameFragment(
    val id: String,
    val data: ByteArray,
    val timestamp: Long,
)

fun processCameraFrame(imageData: ByteArray, memory: FrameMemory): FrameFragment {
    val processed = imageData.map { byte -> (byte.toInt() xor 0xFF).toByte() }.toByteArray()
    return memory.storeFragment(processed)
}

expect fun currentTimeMillis(): Long
