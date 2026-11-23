package android.os

object SystemClock {
    private val t0 = System.nanoTime()
    fun uptimeMillis(): Long = (System.nanoTime() - t0) / 1_000_000
    fun elapsedRealtime(): Long = uptimeMillis()
}

object Build { object VERSION { const val SDK_INT = 36 } }
