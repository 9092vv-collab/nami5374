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

    enum class Kind { Charx, Png, Json, JpegCharx }

    fun detect(head: ByteArray): Kind = when {
        head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Kind.Charx
        head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() -> Kind.Png
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> Kind.JpegCharx
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

    // ---------- module.risum (RisuAI module file holding regex scripts + triggers; module 1063) ----------

    private val RISUM_ENC = intArrayOf(196,13,30,11,189,43,63,85,252,69,110,245,102,83,79,26,224,187,48,148,134,186,107,191,65,80,111,155,239,222,183,16,97,23,32,223,50,137,168,157,109,171,201,144,0,12,93,175,210,193,86,229,22,100,145,130,101,116,151,202,35,214,82,209,255,180,160,232,47,138,88,56,90,96,25,150,73,219,215,200,59,62,67,75,165,99,71,170,106,41,146,244,21,207,98,52,120,211,29,60,226,5,142,42,87,14,27,205,76,45,242,64,44,37,121,72,15,178,122,181,167,108,55,230,156,123,84,126,254,135,220,154,2,228,51,162,235,177,46,3,221,153,166,176,231,213,136,24,131,124,246,190,225,92,159,195,33,70,31,8,78,208,118,18,95,238,253,143,68,234,163,94,139,40,9,53,158,105,204,10,199,133,7,173,74,243,119,233,103,212,218,132,128,147,182,77,115,250,39,38,127,4,198,251,241,114,57,81,194,54,169,104,172,248,237,197,185,203,206,117,164,61,129,217,66,112,28,149,17,188,216,140,152,249,89,161,19,247,20,125,179,236,113,192,227,141,240,1,174,91,49,6,36,34,58,184)
    private val RISUM_DEC = IntArray(256).also { d -> for (u in 0 until 256) d[RISUM_ENC[u]] = u }

    fun readRisum(b: ByteArray): JsonObject? {
        if (b.size < 6 || (b[0].toInt() and 0xFF) != 111 || b[1].toInt() != 0) return null
        val len = (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8) or ((b[4].toInt() and 0xFF) shl 16) or ((b[5].toInt() and 0xFF) shl 24)
        if (len < 0 || 6 + len > b.size) return null
        val out = ByteArray(len) { i -> RISUM_DEC[b[6 + i].toInt() and 0xFF].toByte() }
        return runCatching {
            val o = json.parseToJsonElement(String(out, Charsets.UTF_8)) as JsonObject
            if (str(o, "type") == "risuModule") o["module"] as? JsonObject else null
        }.getOrNull()
    }

    fun buildRisum(module: JsonObject): ByteArray {
        val wrapped = buildJsonObject { put("module", JsonObject(module + ("assets" to JsonArray(emptyList())))); put("type", JsonPrimitive("risuModule")) }
        val u = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), wrapped).toByteArray(Charsets.UTF_8)
        val o = ByteArray(6 + u.size + 1)
        o[0] = 111; o[1] = 0
        o[2] = (u.size and 0xFF).toByte(); o[3] = ((u.size shr 8) and 0xFF).toByte(); o[4] = ((u.size shr 16) and 0xFF).toByte(); o[5] = ((u.size shr 24) and 0xFF).toByte()
        for (i in u.indices) o[6 + i] = RISUM_ENC[u[i].toInt() and 0xFF].toByte()
        return o
    }

    /** Like the original import: module.risum's trigger/regex replace the card's extensions.risuai ones. */
    fun mergeRisum(card: JsonObject, xDir: File): JsonObject {
        val f = File(xDir, "module.risum")
        if (!f.exists()) return card
        val m = readRisum(f.readBytes()) ?: return card
        val data = card["data"] as? JsonObject ?: return card
        val ext = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val risu = ext["risuai"] as? JsonObject ?: JsonObject(emptyMap())
        val newRisu = JsonObject(risu + mapOf("triggerscript" to (m["trigger"] ?: JsonArray(emptyList())), "customScripts" to (m["regex"] ?: JsonArray(emptyList()))))
        return JsonObject(card + ("data" to JsonObject(data + ("extensions" to JsonObject(ext + ("risuai" to newRisu))))))
    }

    /** Like the original export: triggers/regex move from card.json into a fresh module.risum. */
    fun splitRisum(card: JsonObject): Pair<JsonObject, ByteArray?> {
        val data = card["data"] as? JsonObject ?: return card to null
        val ext = data["extensions"] as? JsonObject ?: return card to null
        val risu = ext["risuai"] as? JsonObject ?: return card to null
        val name = str(data, "name").ifBlank { "Character" }
        val module = buildJsonObject {
            put("name", JsonPrimitive("$name Module")); put("description", JsonPrimitive("Module for $name"))
            put("id", JsonPrimitive(java.util.UUID.randomUUID().toString()))
            put("trigger", risu["triggerscript"] ?: JsonArray(emptyList())); put("regex", risu["customScripts"] ?: JsonArray(emptyList()))
        }
        val cleaned = JsonObject(risu - "triggerscript" - "customScripts")
        val newCard = JsonObject(card + ("data" to JsonObject(data + ("extensions" to JsonObject(ext + ("risuai" to cleaned))))))
        return newCard to buildRisum(module)
    }

    fun writeCharx(out: OutputStream, card0: JsonObject, xDir: File?, extra: Map<String, File>) {
        val (card, risum) = splitRisum(card0)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("card.json"))
            zip.write(json.encodeToString(JsonElement.serializer(), card).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (xDir != null && xDir.exists()) {
                xDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(xDir).invariantSeparatorsPath
                    if (rel == "card.json" || rel == "module.risum") return@forEach
                    zip.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (risum != null) {
                zip.putNextEntry(ZipEntry("module.risum")); zip.write(risum); zip.closeEntry()
            }
            extra.forEach { (name, f) ->
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
