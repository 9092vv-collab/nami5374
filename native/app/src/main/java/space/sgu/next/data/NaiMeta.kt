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
        val c = chunks(file)
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
}
