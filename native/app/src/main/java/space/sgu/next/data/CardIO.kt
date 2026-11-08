package space.sgu.next.data

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Character card helpers (Character Card V1/V2/V3, PNG cards, CHARX). Everything is kept as raw JSON
 *  so fields this app does not know about (RisuAI extensions, lorebook, scripts…) survive round trips. */
object CardIO {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    enum class Kind { Charx, Png, Json }

    fun detect(head: ByteArray): Kind = when {
        head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Kind.Charx
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() -> Kind.Png
        else -> Kind.Json
    }

    /** Wraps any card version into V3 shape: {spec, spec_version, data:{…}}. */
    fun normalize(raw: JsonObject): JsonObject {
        val data = (raw["data"] as? JsonObject) ?: JsonObject(raw.filterKeys { it != "spec" && it != "spec_version" })
        return buildJsonObject {
            put("spec", JsonPrimitive("chara_card_v3"))
            put("spec_version", JsonPrimitive("3.0"))
            put("data", data)
        }
    }

    fun parseJson(text: String): JsonObject = normalize(json.parseToJsonElement(text).jsonObject)

    /** Reads the embedded card JSON from a PNG's tEXt chunks ("ccv3" preferred, then "chara"). */
    fun cardFromPng(bytes: ByteArray): JsonObject {
        var pos = 8
        var v3: String? = null
        var v2: String? = null
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val start = pos + 8
            if (len < 0 || start + len > bytes.size) break
            if (type == "tEXt") {
                val chunk = bytes.copyOfRange(start, start + len)
                val zero = chunk.indexOf(0.toByte())
                if (zero > 0) {
                    val key = String(chunk, 0, zero, Charsets.ISO_8859_1)
                    val value = String(chunk, zero + 1, chunk.size - zero - 1, Charsets.ISO_8859_1)
                    if (key.equals("ccv3", true)) v3 = value
                    if (key.equals("chara", true)) v2 = value
                }
            }
            if (type == "IEND") break
            pos = start + len + 4
        }
        val b64 = v3 ?: v2 ?: error("이 PNG에는 캐릭터 정보가 없어요")
        val text = String(Base64.decode(b64.trim(), Base64.DEFAULT), Charsets.UTF_8)
        return parseJson(text)
    }

    /** Unzips a CHARX into [dest] (zip-slip safe). Returns the parsed card.json. */
    fun unzipCharx(input: InputStream, dest: File): JsonObject {
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val cardFile = File(dest, "card.json")
        if (!cardFile.exists()) error("CHARX 안에 card.json이 없어요")
        return parseJson(cardFile.readText())
    }

    /** File for an asset uri like "embeded://assets/icon/image/1.png" inside an unzipped CHARX dir. */
    fun assetFile(xDir: File, uri: String): File? {
        val p = when {
            uri.startsWith("embeded://") -> uri.removePrefix("embeded://")
            uri.startsWith("embedded://") -> uri.removePrefix("embedded://")
            else -> return null
        }
        return File(xDir, p).takeIf { it.exists() }
    }

    fun assets(card: JsonObject): List<JsonObject> =
        ((card["data"] as? JsonObject)?.get("assets") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun str(o: JsonObject?, key: String): String = ((o?.get(key)) as? JsonPrimitive)?.contentOrNull ?: ""

    /** Writes a CHARX: card.json + files under [xDir] (if any) + [extra] entries. */
    fun writeCharx(out: OutputStream, card: JsonObject, xDir: File?, extra: Map<String, File>) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("card.json"))
            zip.write(json.encodeToString(JsonElement.serializer(), card).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (xDir != null && xDir.exists()) {
                xDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(xDir).invariantSeparatorsPath
                    if (rel == "card.json") return@forEach
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            extra.forEach { (name, f) ->
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
