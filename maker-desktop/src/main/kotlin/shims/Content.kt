@file:JvmName("MakerContentShimsKt")
package android.content

import java.io.File

/** 안드로이드 Context 흉내: 데이터 폴더만 알려 줌. */
open class Context(val dataDir: File) {
    open val filesDir: File get() = File(dataDir, "files").apply { mkdirs() }
    open val cacheDir: File get() = File(dataDir, "cache").apply { mkdirs() }
}
