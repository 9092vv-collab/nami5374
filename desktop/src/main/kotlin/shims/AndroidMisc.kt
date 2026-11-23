@file:Suppress("unused")
package android.content.res

class AssetManager {
    fun open(name: String): java.io.InputStream =
        AssetManager::class.java.classLoader.getResourceAsStream("assets/$name") ?: throw java.io.FileNotFoundException(name)
}
