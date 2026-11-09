package space.sgu.next.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/** 내보내기 (original modules 1651 screen + 1652 exportCharacter): CHARX, CHARX-JPEG, CCv3 JSON, PNG card. */
object Exporter {
    enum class Format(val id: String, val label: String, val ext: String, val mime: String, val hint: String) {
        Charx("charx", "CHARX", "charx", "application/octet-stream", "RisuAI 표준. 모든 에셋이 ZIP 안에 포함됩니다."),
        CharxJpeg("charx-jpeg", "CHARX (JPEG 임베디드)", "jpeg", "image/jpeg", "아이콘 JPEG 뒤에 CHARX를 붙인 파일. 이미지 뷰어에선 사진으로, RisuAI에선 카드로 열립니다."),
        JsonCard("json", "JSON", "json", "application/json", "CCv3 JSON. 에셋은 base64로 인라인됩니다."),
        Png("png", "PNG 카드", "png", "image/png", "아이콘 PNG에 카드 데이터를 심은 전통적 형식(ccv3 청크)."),
    }

    data class Summary(val icons: Int, val emotions: Int, val extra: Int, val lore: Int, val regex: Int, val trigger: String?,
                       val desc: Int, val first: Int, val alt: Int)

    fun summary(cid: String): Summary {
        val card = CharacterStore.readCard(cid)
        val data = card["data"] as? JsonObject
        val assets = CardIO.assets(card)
        val trig = Scripts.trigger(data)
        return Summary(
            icons = CharIcons.list(cid).size,
            emotions = assets.count { CardIO.str(it, "type") == "emotion" },
            extra = assets.count { CardIO.str(it, "type") !in setOf("icon", "emotion") },
            lore = Scripts.lore(data).size,
            regex = Scripts.objects(data, Scripts.REGEX_PATH).size,
            trigger = if (trig.code.isBlank()) null else if (trig.mode == Scripts.TriggerMode.V2) "v2" else "lua",
            desc = CardIO.str(data, "description").length,
            first = CardIO.str(data, "first_mes").length,
            alt = ((data?.get("alternate_greetings")) as? JsonArray)?.size ?: 0,
        )
    }

    fun safeFileName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]+"), "_").trim().ifBlank { "character" }.take(100)

    fun fileName(cid: String, f: Format): String {
        val base = safeFileName(CardIO.str(CharacterStore.readCard(cid)["data"] as? JsonObject, "name"))
        return if (f == Format.JsonCard) "${base}_export.json" else "$base.${f.ext}"
    }

    /** Writes the export to [out] (IO thread). */
    fun build(cid: String, f: Format, out: File) {
        when (f) {
            Format.Charx -> out.outputStream().buffered().use { CharacterStore.writeCharxTo(cid, it) }
            Format.CharxJpeg -> out.outputStream().buffered().use { o ->
                o.write(iconBytes(cid, jpeg = true))
                CharacterStore.writeCharxTo(cid, o)
            }
            Format.JsonCard -> {
                val (card, files) = resolved(cid)
                val assets = CardIO.assets(card).mapIndexed { i, a ->
                    val bytes = if (isMain(a)) iconBytes(cid, jpeg = false) else files[i]?.readBytes()
                    if (bytes == null) a else JsonObject(a + ("uri" to JsonPrimitive("data:application/octet-stream;base64," + b64(bytes))))
                }
                out.writeText(pretty.encodeToString(JsonElement.serializer(), withAssets(card, assets)))
            }
            Format.Png -> {
                val (card, files) = resolved(cid)
                val chunks = mutableListOf<Pair<String, String>>()
                var n = 0
                val assets = CardIO.assets(card).mapIndexed { i, a ->
                    if (isMain(a)) return@mapIndexed a
                    val bytes = files[i]?.readBytes() ?: return@mapIndexed a
                    n += 1
                    chunks.add("chara-ext-asset_:$n" to b64(bytes))
                    JsonObject(a + ("uri" to JsonPrimitive("__asset:$n")))
                }
                val text = compact.encodeToString(JsonElement.serializer(), withAssets(card, assets))
                val enc = b64(text.toByteArray(Charsets.UTF_8))
                chunks.add("chara" to enc); chunks.add("ccv3" to enc)
                out.outputStream().buffered().use { writeTextChunks(iconBytes(cid, jpeg = false), chunks, it) }
            }
        }
    }

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "    " }
    private val compact = Json
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun isMain(a: JsonObject) = CardIO.str(a, "type") == "icon" && CardIO.str(a, "name") == "main"

    private fun withAssets(card: JsonObject, assets: List<JsonObject>): JsonObject {
        val data = (card["data"] as? JsonObject).orEmpty()
        return JsonObject(card + ("data" to JsonObject(data + ("assets" to JsonArray(assets)))))
    }

    /** Card (main icon asset guaranteed) + the file of each asset index. */
    private fun resolved(cid: String): Pair<JsonObject, Map<Int, File>> {
        val (card, extra) = CharacterStore.cardWithMainIcon(cid)
        val x = File(CharacterStore.dir(cid), "x")
        val files = mutableMapOf<Int, File>()
        CardIO.assets(card).forEachIndexed { i, a ->
            val uri = CardIO.str(a, "uri")
            val f = CardIO.assetFile(x, uri) ?: extra[uri.removePrefix("embeded://").removePrefix("embedded://")]?.takeIf { it.exists() }
            if (f != null) files[i] = f
        }
        return card to files
    }

    private fun mainAssetFile(cid: String): File? = CardIO.assets(CharacterStore.readCard(cid)).firstOrNull { isMain(it) }
        ?.let { CardIO.assetFile(File(CharacterStore.dir(cid), "x"), CardIO.str(it, "uri")) }

    // 1×1 placeholders the original uses when there is no icon
    private const val BLANK_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
    private const val BLANK_JPEG = "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/yQALCAABAAEBAREA/8wABgAQEAX/2gAIAQEAAD8A0s8g/9k="

    /** Main icon as PNG (kept as is when it already is one) or JPEG 92%. */
    private fun iconBytes(cid: String, jpeg: Boolean): ByteArray {
        val f = CharIcons.list(cid).firstOrNull { it.main }?.file ?: mainAssetFile(cid)
        if (f != null && f.exists()) {
            val raw = f.readBytes()
            val isPng = raw.size > 8 && raw[0] == 0x89.toByte() && raw[1] == 'P'.code.toByte()
            if (!jpeg && isPng) return raw
            BitmapFactory.decodeByteArray(raw, 0, raw.size)?.let { bmp ->
                val bo = ByteArrayOutputStream()
                if (jpeg) {
                    // JPEG has no alpha: draw on white like the original's toJpeg
                    val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                    flat.compress(Bitmap.CompressFormat.JPEG, 92, bo); flat.recycle()
                } else bmp.compress(Bitmap.CompressFormat.PNG, 100, bo)
                bmp.recycle()
                return bo.toByteArray()
            }
        }
        return Base64.decode(if (jpeg) BLANK_JPEG else BLANK_PNG, Base64.DEFAULT)
    }

    /** Inserts tEXt chunks before IEND, dropping old card chunks (chara / ccv3 / chara-ext-asset_*). */
    private fun writeTextChunks(png: ByteArray, chunks: List<Pair<String, String>>, out: java.io.OutputStream) {
        out.write(png, 0, 8)
        var pos = 8
        fun int(p: Int) = ((png[p].toInt() and 0xFF) shl 24) or ((png[p + 1].toInt() and 0xFF) shl 16) or ((png[p + 2].toInt() and 0xFF) shl 8) or (png[p + 3].toInt() and 0xFF)
        while (pos + 8 <= png.size) {
            val len = int(pos)
            val type = String(png, pos + 4, 4, Charsets.ISO_8859_1)
            val end = pos + 12 + len
            if (len < 0 || end > png.size) break
            if (type == "IEND") {
                for ((k, v) in chunks) writeChunk(out, "tEXt", k.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + v.toByteArray(Charsets.ISO_8859_1))
                out.write(png, pos, png.size - pos)
                return
            }
            val drop = type == "tEXt" && run {
                val body = png.copyOfRange(pos + 8, pos + 8 + len)
                val key = String(body, 0, body.indexOf(0.toByte()).let { if (it < 0) body.size else it }, Charsets.ISO_8859_1).lowercase()
                key == "chara" || key == "ccv3" || key.startsWith("chara-ext-asset_")
            }
            if (!drop) out.write(png, pos, end - pos)
            pos = end
        }
        // no IEND found: append the chunks and a fresh IEND
        for ((k, v) in chunks) writeChunk(out, "tEXt", k.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + v.toByteArray(Charsets.ISO_8859_1))
        writeChunk(out, "IEND", ByteArray(0))
    }

    private fun writeChunk(out: java.io.OutputStream, type: String, data: ByteArray) {
        fun i32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(i32(data.size)); out.write(t); out.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        out.write(i32(crc.value.toInt()))
    }
}
