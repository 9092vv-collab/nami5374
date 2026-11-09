package space.sgu.next.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RisuAI 프롬프트 프리셋 (.risup / .risupreset / .json) 읽기·쓰기 — RisuAI src/ts/storage/database.svelte.ts
 * importPreset / downloadPreset 와 같은 순서:
 *   .risup = rpack( gzip( msgpack{ presetVersion:2, type:"preset", preset: AES-GCM(msgpack(preset)) } ) )
 *   AES 키 = SHA-256("risupreset"), IV = 0 × 12.
 * rpack 치환표는 RisuAI src/ts/rpack/rpack_map.bin (Rpack.js, MIT / AGPL-3.0 — native/THIRD_PARTY_NOTICES.md 참고).
 * 바이너리 값은 {"$bin": base64}, msgpack 확장은 {"$ext": 타입, "$data": base64} 로 보관해 그대로 되돌려 쓴다.
 */
object RisuPreset {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val ENC = hex("c40d1e0bbd2b3f55fc456ef566534f1ae0bb309486ba6bbf41506f9befdeb710611720df3289a89d6dabc990000c5dafd2c156e516649182657497ca23d652d1ffb4a0e82f8a58385a60199649dbd7c83b3e434ba56347aa6a2992f415cf623478d31d3ce2058e2a570e1bcd4c2df2402c2579480fb27ab5a76c37e69c7b547efe87dc9a02e433a2ebb12e03dd99a6b0e7d58818837cf6bee15c9fc321461f084ed076125feefd8f44eaa35e8b2809359e69cc0ac78507ad4af377e967d4da848093b64d73fa27267f04c6fbf1723951c236a968acf8edc5b9cbce75a43d81d942701c9511bcd88c98f959a113f7147db3ec71c0e38df001ae5b310624223ab8")
    private val DEC = hex("2cf7848bc965fbb69faeb3032d0169741fe4a3ecee5c3421934a0f6ae262029e229cfd3cfc71c7c6ad596705706d8a4412fa24865fafd17a47cefe5063dd51066f18e052a8099d56734cb8536cc3a00e19cf3e0d7e07326846ea48f9992eaba449205e5535380cbcd3b1581679280a1ae1f2cdc439dba2ba6072767d95ef7fc8c0de3794bfb51481922545ace7f566a72b365ac113e34b3ae88d831b7c27b09a42eb87aadc548e7826d25729d4b7f82f8f8975f04177c21effd81511e5049717f331d09b00d7cab44f2a3bd9b26bda5da13f3061bd913d4ee6dfbe4d828c1d23109864f485337b9043bba988f1d6a51cf6cc6eb95b0b96edd5e9c5cb08a68040")

    private fun rpack(b: ByteArray, map: ByteArray) = ByteArray(b.size) { map[b[it].toInt() and 0xFF] }

    private fun key(): SecretKeySpec = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("risupreset".toByteArray()), "AES")
    private fun aes(mode: Int, b: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(mode, key(), GCMParameterSpec(128, ByteArray(12))); doFinal(b) }

    private fun inflate(b: ByteArray): ByteArray = when {
        b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte() -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        b.isNotEmpty() && b[0] == 0x78.toByte() -> InflaterInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        else -> InflaterInputStream(ByteArrayInputStream(b), Inflater(true)).use { it.readBytes() }
    }

    class NotPreset(msg: String) : Exception(msg)

    /** Bytes of a picked file → RisuAI preset object. */
    fun decode(bytes: ByteArray, fileName: String): JsonObject {
        val name = fileName.lowercase()
        val obj: JsonObject = if (name.endsWith(".risup") || name.endsWith(".risupreset")) {
            val data = if (name.endsWith(".risup")) rpack(bytes, DEC) else bytes
            val outer = MsgPack.read(inflate(data)) as? JsonObject ?: throw NotPreset("RisuAI 프리셋 파일이 아니에요")
            val ver = (outer["presetVersion"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
            if ((ver != 0 && ver != 2) || outer.s("type") != "preset") throw NotPreset("지원하지 않는 프리셋 버전이에요")
            val enc = binOf(outer["preset"] ?: outer["pres"]) ?: throw NotPreset("프리셋 내용이 없어요")
            MsgPack.read(aes(Cipher.DECRYPT_MODE, enc)) as? JsonObject ?: throw NotPreset("프리셋 내용을 읽지 못했어요")
        } else CardIO.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        if (obj["promptTemplate"] !is JsonArray && obj["mainPrompt"] == null && obj["jailbreak"] == null)
            throw NotPreset("RisuAI 프롬프트 프리셋이 아니에요 (NovelAI·SillyTavern 프리셋은 아직 못 읽어요)")
        return obj
    }

    fun read(c: Context, u: Uri): JsonObject {
        val name = runCatching {
            c.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: u.lastPathSegment ?: "preset"
        val bytes = c.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw NotPreset("파일을 열 수 없어요")
        // 확장자가 없거나 바뀐 파일: JSON이 아니면 .risup로 시도
        return if (name.contains('.')) decode(bytes, name) else runCatching { decode(bytes, "$name.json") }.getOrElse { decode(bytes, "$name.risup") }
    }

    /** RisuAI 내보내기와 같은 .risup 바이트. 키 관련 칸은 원본처럼 비운다. */
    fun encodeRisup(p0: JsonObject): ByteArray {
        val p = scrub(p0)
        val inner = aes(Cipher.ENCRYPT_MODE, MsgPack.write(p))
        val outer = MsgPack.write(JsonObject(mapOf("presetVersion" to JsonPrimitive(2), "type" to JsonPrimitive("preset"), "preset" to bin(inner))))
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(outer) } }.toByteArray()
        return rpack(gz, ENC)
    }

    private val pretty = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  " }
    fun encodeJson(p: JsonObject): ByteArray = pretty.encodeToString(JsonElement.serializer(), scrub(p)).toByteArray(Charsets.UTF_8)

    private fun scrub(p: JsonObject) = JsonObject(p + listOf("openAIKey", "forceReplaceUrl", "forceReplaceUrl2", "proxyKey", "textgenWebUIStreamURL", "textgenWebUIBlockingURL")
        .filter { p.containsKey(it) }.associateWith { JsonPrimitive("") })

    fun bin(b: ByteArray) = JsonObject(mapOf("\$bin" to JsonPrimitive(Base64.encodeToString(b, Base64.NO_WRAP))))
    fun binOf(e: JsonElement?): ByteArray? = (e as? JsonObject)?.s("\$bin")?.let { Base64.decode(it, Base64.DEFAULT) }

    // ---------- 프롬프트 템플릿 항목 ----------

    val TEXT_TYPES = setOf("plain", "jailbreak", "cot", "chatML")
    val FORMAT_TYPES = setOf("persona", "description", "lorebook", "postEverything", "memory", "authornote")

    fun items(p: JsonObject): List<JsonObject> = (p["promptTemplate"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    fun typeLabel(x: JsonObject): String = when (x.s("type")) {
        "plain" -> when (x.s("type2")) { "main" -> "메인 프롬프트"; "globalNote" -> "글로벌 노트"; else -> "일반 프롬프트" }
        "jailbreak" -> "탈옥 프롬프트"
        "cot" -> "생각의 사슬(CoT)"
        "chatML" -> "ChatML"
        "persona" -> "페르소나"
        "description" -> "캐릭터 설명"
        "lorebook" -> "로어북"
        "postEverything" -> "모든 것 뒤에"
        "memory" -> "장기 기억"
        "authornote" -> "작가의 노트"
        "chat" -> "채팅"
        "cache" -> "캐시"
        else -> x.s("type")
    }

    fun roleLabel(r: String) = when (r) { "user" -> "유저"; "bot" -> "봇"; "system" -> "시스템"; "assistant" -> "어시스턴트"; "all" -> "전체"; else -> r.ifBlank { "시스템" } }

    /** Text the item carries (text / innerFormat) and the key it lives in. */
    fun textKey(x: JsonObject): String? = when (x.s("type")) { in TEXT_TYPES -> "text"; in FORMAT_TYPES -> "innerFormat"; else -> null }

    fun newPlain(): JsonObject = JsonObject(mapOf("type" to JsonPrimitive("plain"), "type2" to JsonPrimitive("normal"), "text" to JsonPrimitive(""), "role" to JsonPrimitive("system")))

    /**
     * SGU 작업실 프리셋 블록으로: RisuAI 텍스트 블록(메인·일반·탈옥·CoT·글로벌 노트)을 SGU 기본 "JB" 자리에 넣고,
     * 나머지 SGU 기본 블록(캐릭터 데이터·출력 형식 등)은 그대로. 롤플레이 전용 칸(설명·페르소나·로어북·채팅 등)은 건너뛴다.
     * 봇(어시스턴트) 프리필은 SGU의 JSON 출력과 부딪혀서 꺼진 채로 넣는다.
     */
    fun toSguBlocks(p: JsonObject): Pair<List<JsonObject>, Int> {
        val out = mutableListOf<JsonObject>()
        var skipped = 0
        fun add(name: String, role: String, text: String, enabled: Boolean) {
            if (text.isBlank()) return
            out.add(JsonObject(mapOf("id" to JsonPrimitive(Scripts.newId("blk")), "type" to JsonPrimitive("text"), "enabled" to JsonPrimitive(enabled),
                "role" to JsonPrimitive(role), "name" to JsonPrimitive(name), "text" to JsonPrimitive(text))))
        }
        val list = items(p)
        if (list.isEmpty()) {
            add("메인 프롬프트", "system", p.s("mainPrompt"), true)
            add("탈옥 프롬프트", "system", p.s("jailbreak"), true)
            add("글로벌 노트", "system", p.s("globalNote"), true)
        }
        for (it in list) {
            if (it.s("type") !in TEXT_TYPES) { skipped++; continue }
            val role = when (it.s("role")) { "user" -> "user"; "bot" -> "assistant"; else -> "system" }
            val name = "Risu · " + it.s("name").ifBlank { typeLabel(it) }
            if (it.s("text").isBlank()) { skipped++; continue }
            add(name, role, it.s("text"), role != "assistant")
        }
        val sgu = PromptBlocks.defaults().filterNot { it.s("type") == "text" && it.s("name") == "JB" }
        return (out + sgu) to skipped
    }

    // ---------- MessagePack (msgpackr 기본 출력과 호환되는 표준 형식) ----------

    object MsgPack {
        fun read(b: ByteArray): JsonElement = Reader(b).value()

        private class Reader(val b: ByteArray) {
            var i = 0
            fun u8() = b[i++].toInt() and 0xFF
            fun u16() = (u8() shl 8) or u8()
            fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
            fun i64(): Long { var v = 0L; repeat(8) { v = (v shl 8) or u8().toLong() }; return v }
            fun bytes(n: Int) = b.copyOfRange(i, i + n).also { i += n }
            fun str(n: Int) = String(bytes(n), Charsets.UTF_8)
            fun arr(n: Int) = JsonArray(List(n) { value() })
            fun map(n: Int): JsonObject {
                val m = LinkedHashMap<String, JsonElement>()
                repeat(n) { val k = value(); m[(k as? JsonPrimitive)?.contentOrNull ?: k.toString()] = value() }
                return JsonObject(m)
            }
            fun ext(n: Int): JsonElement { val t = b[i++].toInt(); return JsonObject(mapOf("\$ext" to JsonPrimitive(t), "\$data" to JsonPrimitive(Base64.encodeToString(bytes(n), Base64.NO_WRAP)))) }
            fun value(): JsonElement {
                val c = u8()
                return when {
                    c <= 0x7f -> JsonPrimitive(c)
                    c in 0x80..0x8f -> map(c and 0x0f)
                    c in 0x90..0x9f -> arr(c and 0x0f)
                    c in 0xa0..0xbf -> JsonPrimitive(str(c and 0x1f))
                    c >= 0xe0 -> JsonPrimitive(c - 256)
                    else -> when (c) {
                        0xc0 -> JsonNull
                        0xc2 -> JsonPrimitive(false)
                        0xc3 -> JsonPrimitive(true)
                        0xc4 -> bin(bytes(u8())); 0xc5 -> bin(bytes(u16())); 0xc6 -> bin(bytes(u32().toInt()))
                        0xc7 -> ext(u8()); 0xc8 -> ext(u16()); 0xc9 -> ext(u32().toInt())
                        0xca -> JsonPrimitive(java.lang.Float.intBitsToFloat(u32().toInt()).toDouble())
                        0xcb -> JsonPrimitive(java.lang.Double.longBitsToDouble(i64()))
                        0xcc -> JsonPrimitive(u8()); 0xcd -> JsonPrimitive(u16()); 0xce -> JsonPrimitive(u32())
                        0xcf -> JsonPrimitive(i64().toULong().toString().toBigInteger())
                        0xd0 -> JsonPrimitive(b[i++].toInt())
                        0xd1 -> JsonPrimitive(u16().toShort().toInt())
                        0xd2 -> JsonPrimitive(u32().toInt())
                        0xd3 -> JsonPrimitive(i64())
                        0xd4 -> ext(1); 0xd5 -> ext(2); 0xd6 -> ext(4); 0xd7 -> ext(8); 0xd8 -> ext(16)
                        0xd9 -> JsonPrimitive(str(u8())); 0xda -> JsonPrimitive(str(u16())); 0xdb -> JsonPrimitive(str(u32().toInt()))
                        0xdc -> arr(u16()); 0xdd -> arr(u32().toInt())
                        0xde -> map(u16()); 0xdf -> map(u32().toInt())
                        else -> throw NotPreset("msgpack 형식 오류 (0x${c.toString(16)})")
                    }
                }
            }
        }

        fun write(e: JsonElement): ByteArray = ByteArrayOutputStream().also { w(it, e) }.toByteArray()

        private fun be(o: ByteArrayOutputStream, v: Long, n: Int) { for (k in n - 1 downTo 0) o.write(((v ushr (8 * k)) and 0xFF).toInt()) }
        private fun len(o: ByteArrayOutputStream, n: Int, fix: Int?, fixMax: Int, c8: Int?, c16: Int, c32: Int) = when {
            fix != null && n <= fixMax -> o.write(fix or n)
            c8 != null && n <= 0xff -> { o.write(c8); o.write(n) }
            n <= 0xffff -> { o.write(c16); be(o, n.toLong(), 2) }
            else -> { o.write(c32); be(o, n.toLong(), 4) }
        }

        private fun w(o: ByteArrayOutputStream, e: JsonElement) {
            when (e) {
                is JsonNull -> o.write(0xc0)
                is JsonObject -> {
                    binOf(e)?.takeIf { e.size == 1 }?.let { d -> len(o, d.size, null, 0, 0xc4, 0xc5, 0xc6); o.write(d); return }
                    if (e.size == 2 && e.containsKey("\$ext") && e.containsKey("\$data")) {
                        val d = Base64.decode(e.s("\$data"), Base64.DEFAULT); val t = e.s("\$ext").toInt()
                        when (d.size) { 1 -> o.write(0xd4); 2 -> o.write(0xd5); 4 -> o.write(0xd6); 8 -> o.write(0xd7); 16 -> o.write(0xd8)
                            else -> len(o, d.size, null, 0, 0xc7, 0xc8, 0xc9) }
                        o.write(t and 0xFF); o.write(d); return
                    }
                    len(o, e.size, 0x80, 15, null, 0xde, 0xdf)
                    e.forEach { (k, v) -> w(o, JsonPrimitive(k)); w(o, v) }
                }
                is JsonArray -> { len(o, e.size, 0x90, 15, null, 0xdc, 0xdd); e.forEach { w(o, it) } }
                is JsonPrimitive -> {
                    if (e.isString) { val d = e.content.toByteArray(Charsets.UTF_8); len(o, d.size, 0xa0, 31, 0xd9, 0xda, 0xdb); o.write(d); return }
                    e.booleanOrNull?.let { o.write(if (it) 0xc3 else 0xc2); return }
                    val t = e.content
                    val l = if (t.none { it == '.' || it == 'e' || it == 'E' }) t.toLongOrNull() else null
                    when {
                        l == null -> { o.write(0xcb); be(o, java.lang.Double.doubleToLongBits(t.toDouble()), 8) }
                        l in 0L..0x7fL -> o.write(l.toInt())
                        l in -32L..-1L -> o.write((l + 256).toInt())
                        l in 0L..0xffL -> { o.write(0xcc); be(o, l, 1) }
                        l in 0L..0xffffL -> { o.write(0xcd); be(o, l, 2) }
                        l in 0L..0xffffffffL -> { o.write(0xce); be(o, l, 4) }
                        l in -128L..127L -> { o.write(0xd0); be(o, l, 1) }
                        l in -32768L..32767L -> { o.write(0xd1); be(o, l, 2) }
                        l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> { o.write(0xd2); be(o, l, 4) }
                        else -> { o.write(0xd3); be(o, l, 8) }
                    }
                }
            }
        }
    }
}
