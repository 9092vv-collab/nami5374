package space.sgu.next.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.DataInputStream
import java.io.File
import java.util.zip.InflaterInputStream

/**
 * NovelAI가 PNG 안에 넣는 생성 정보 읽기 (tEXt / zTXt / iTXt 청크).
 * "Comment"에 JSON(prompt, uc, steps, scale, sampler, seed, v4_prompt …), "Description"에 프롬프트, "Software"/"Source"에 모델 정보.
 */
object NaiMeta {
    data class Row(val label: String, val value: String)

    fun chunks(file: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { d ->
                val sig = ByteArray(8); d.readFully(sig)
                if (sig[1] != 'P'.code.toByte() || sig[2] != 'N'.code.toByte()) return out
                while (true) {
                    val len = d.readInt()
                    val type = ByteArray(4).also { d.readFully(it) }.toString(Charsets.ISO_8859_1)
                    if (type == "IDAT" || type == "IEND") { if (type == "IEND") break; d.skipBytes(len + 4); continue }
                    val data = ByteArray(len).also { d.readFully(it) }; d.skipBytes(4)
                    val z = data.indexOf(0)
                    if (z <= 0) continue
                    val key = String(data, 0, z, Charsets.ISO_8859_1)
                    when (type) {
                        "tEXt" -> out[key] = String(data, z + 1, len - z - 1, Charsets.UTF_8)
                        "zTXt" -> out[key] = InflaterInputStream(data.inputStream(z + 2, len - z - 2)).readBytes().toString(Charsets.UTF_8)
                        "iTXt" -> {
                            val compressed = data[z + 1].toInt() == 1
                            var i = z + 3
                            repeat(2) { while (i < len && data[i].toInt() != 0) i++; i++ } // 언어 태그, 번역된 키워드
                            val body = data.copyOfRange(i.coerceAtMost(len), len)
                            out[key] = (if (compressed) InflaterInputStream(body.inputStream()).readBytes() else body).toString(Charsets.UTF_8)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun ByteArray.inputStream(off: Int, n: Int) = java.io.ByteArrayInputStream(this, off, n)

    /** 화면에 보일 항목들 (비어 있으면 메타데이터 없음). */
    fun rows(file: File): List<Row> {
        val c = anyMeta(file)
        val rows = mutableListOf<Row>()
        val j = c["Comment"]?.let { runCatching { CardIO.json.parseToJsonElement(it).jsonObject }.getOrNull() }
        fun p(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
        if (j != null) {
            p(j, "prompt")?.let { rows.add(Row("프롬프트", it)) }
            p(j, "uc")?.let { rows.add(Row("네거티브", it)) }
            val caps = ((j["v4_prompt"] as? JsonObject)?.get("caption") as? JsonObject)?.get("char_captions") as? JsonArray
            val negs = ((j["v4_negative_prompt"] as? JsonObject)?.get("caption") as? JsonObject)?.get("char_captions") as? JsonArray
            caps?.forEachIndexed { i, e -> p(e as? JsonObject, "char_caption")?.takeIf { it.isNotBlank() }?.let { rows.add(Row("캐릭터 ${i + 1} 프롬프트", it)) } }
            negs?.forEachIndexed { i, e -> p(e as? JsonObject, "char_caption")?.takeIf { it.isNotBlank() }?.let { rows.add(Row("캐릭터 ${i + 1} 네거티브", it)) } }
            val s = listOfNotNull(
                p(j, "width")?.let { w -> "크기 ${w}×${p(j, "height")}" }, p(j, "steps")?.let { "스텝 $it" }, p(j, "scale")?.let { "가이던스 $it" },
                p(j, "cfg_rescale")?.let { "리스케일 $it" }, p(j, "sampler")?.let { "샘플러 $it" }, p(j, "noise_schedule")?.let { "스케줄 $it" }, p(j, "seed")?.let { "시드 $it" },
            )
            if (s.isNotEmpty()) rows.add(Row("설정", s.joinToString(" · ")))
        } else c["Description"]?.let { rows.add(Row("프롬프트", it)) }
        listOfNotNull(c["Source"], c["Software"]).joinToString(" · ").takeIf { it.isNotBlank() }?.let { rows.add(Row("모델", it)) }
        if (j != null) rows.add(Row("원본 (Comment)", CardIO.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), j)))
        return rows
    }

    /**
     * 그림체 가져오기: 캐릭터 프롬프트를 뺀 긍정·부정(v4 base_caption, 없으면 prompt·uc)과 모델·샘플러·스텝만.
     * StyleLibrary 데이터 모양으로 돌려줌 (메타데이터 없으면 null).
     */
    /**
     * PNG 글자 칸 → 없으면 투명도 칸에 숨긴 정보(stealth_pngcomp / pnginfo) → 없으면 파일 안의 JSON(webp·jpg EXIF 등).
     * Comment(JSON 문자열)·Source·Software 를 돌려줌.
     */
    fun anyMeta(file: File): Map<String, String> {
        val c = chunks(file)
        if (c["Comment"] != null) return c
        runCatching { stealth(file) }.getOrNull()?.takeIf { it["Comment"] != null }?.let { return it }
        runCatching { scanJson(file.readBytes()) }.getOrNull()?.let { return mapOf("Comment" to it) + c }
        return c
    }

    /** NovelAI가 알파 채널 맨 아래 비트에 숨긴 정보 (열 단위로 읽음). */
    private fun stealth(file: File): Map<String, String>? {
        val opt = android.graphics.BitmapFactory.Options().apply { inPremultiplied = false; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path, opt) ?: return null
        try {
            if (!bmp.hasAlpha()) return null
            val w = bmp.width; val h = bmp.height
            val col = IntArray(h)
            var x = 0; var y = h
            fun bit(): Int {
                if (y >= h) { if (x >= w) throw IllegalStateException(); bmp.getPixels(col, 0, 1, x, 0, 1, h); x++; y = 0 }
                return (col[y++] ushr 24) and 1
            }
            fun bytes(n: Int) = ByteArray(n) { var v = 0; repeat(8) { v = (v shl 1) or bit() }; v.toByte() }
            val magic = String(bytes(15), Charsets.ISO_8859_1)
            val zipped = when (magic) { "stealth_pngcomp" -> true; "stealth_pnginfo" -> false; else -> return null }
            var bits = 0; repeat(32) { bits = (bits shl 1) or bit() }
            if (bits <= 0 || bits / 8 > w * h) return null
            val raw = bytes(bits / 8)
            val text = (if (zipped) java.util.zip.GZIPInputStream(raw.inputStream()).readBytes() else raw).toString(Charsets.UTF_8)
            val o = CardIO.json.parseToJsonElement(text).jsonObject
            return o.mapNotNull { (k, v) ->
                val str = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: CardIO.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), v)
                k to str
            }.toMap()
        } finally { bmp.recycle() }
    }

    /** 파일 안 어딘가에 들어 있는 NovelAI JSON ({"prompt": … }) 찾기. */
    private fun scanJson(b: ByteArray): String? {
        val t = String(b, Charsets.ISO_8859_1)
        var from = 0
        while (true) {
            val k = t.indexOf("\"prompt\"", from).takeIf { it >= 0 } ?: return null
            from = k + 1
            val start = t.lastIndexOf('{', k).takeIf { it >= 0 } ?: continue
            var depth = 0; var inStr = false; var esc = false; var end = -1
            for (i in start until t.length) {
                val ch = t[i]
                if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
                when (ch) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = i; break } } }
            }
            if (end < 0) continue
            val json = String(t.substring(start, end + 1).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (runCatching { CardIO.json.parseToJsonElement(json).jsonObject }.isSuccess) return json
        }
    }

    fun styleData(file: File): JsonObject? {
        val c = anyMeta(file)
        val j = c["Comment"]?.let { runCatching { CardIO.json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        fun p(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
        fun base(k: String) = p(((j[k] as? JsonObject)?.get("caption") as? JsonObject), "base_caption")
        val par = j["parameters"] as? JsonObject
        val pos = base("v4_prompt") ?: p(j, "prompt") ?: p(j, "input") ?: p(par, "prompt") ?: ""
        val neg = base("v4_negative_prompt") ?: p(j, "uc") ?: p(j, "negative_prompt") ?: p(par, "negative_prompt") ?: ""
        val src = listOfNotNull(c["Source"], c["Software"]).joinToString(" ")
        val model = when {
            src.contains("4.5") -> "nai-diffusion-4-5-full"
            Regex("""V5|5\.0""").containsMatchIn(src) -> when {
                src.contains("medium", true) -> NaiModels.V5_MEDIUM
                src.contains("curated", true) -> "nai-diffusion-5-curated"
                else -> "nai-diffusion-5-full"
            }
            else -> ""
        }
        return kotlinx.serialization.json.buildJsonObject {
            if (model.isNotBlank()) put("model name", JsonPrimitive(model))
            (p(j, "steps") ?: p(par, "steps"))?.toDoubleOrNull()?.let { put("Steps", JsonPrimitive(it.toInt())) }
            (p(j, "sampler") ?: p(par, "sampler"))?.takeIf { s -> NaiModels.samplers.any { it.first == s } }?.let { put("Sampler", JsonPrimitive(it)) }
            put("Art style prompt", JsonPrimitive(pos)); put("Art style negative", JsonPrimitive(neg))
        }
    }
}
