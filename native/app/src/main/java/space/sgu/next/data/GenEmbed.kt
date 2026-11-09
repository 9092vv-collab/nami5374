package space.sgu.next.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 이미지 템플릿(캐릭터 설정 태그 블록 + 프리셋 + 그림체, 그림 파일 제외)을 카드의 정규식 스크립트 하나에 실어 나른다 (사용자 아이디어).
 * - 정규식은 AI에게 보내지지 않아 토큰을 안 쓰고, 카드의 정식 칸이라 RisuAI에서 다시 내보내도 남는다.
 * - IN = `(?!)` (절대 안 걸림) + disabled → RisuAI에서 아무 동작도 안 함. OUT = JSON.
 * - SGU로 불러올 때 이 정규식을 찾아 이미지 템플릿으로 복원하고 카드에서는 뺀다(스크립트 탭에 안 보임). 내보낼 때 다시 넣는다.
 */
object GenEmbed {
    const val NAME = "SGU 이미지 블록 (수정·삭제 금지)"
    private const val FORMAT = "sgu-image-template"
    private const val NEVER = "(?!)"
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private fun isOurs(r: JsonObject) = CardIO.str(r, "comment") == NAME || (CardIO.str(r, "in") == NEVER && CardIO.str(r, "out").contains("\"$FORMAT\""))

    /** What goes into the regex: everything but generated images / preview / template stash. */
    private fun pack(g: JsonObject): JsonObject = JsonObject(mapOf(
        "format" to JsonPrimitive(FORMAT), "version" to JsonPrimitive(1),
        "templateName" to JsonPrimitive(g.s("templateName")),
        "style" to (g.o("style") ?: JsonObject(emptyMap())),
        "characters" to JsonArray(g.arr("characters")),
        "selectedCharacterId" to (g["selectedCharacterId"] ?: JsonNull),
        "presets" to JsonArray(g.arr("presets").map { JsonObject(it - "images" + ("images" to JsonArray(emptyList()))) }),
    ))

    private fun hasContent(g: JsonObject) = g.arr("presets").isNotEmpty() || g.arr("characters").any { c -> c.arr("blocks").isNotEmpty() || c.s("freePrompt").isNotBlank() }

    /** Export: card with the block regex added / refreshed (or removed when there is nothing to carry). */
    fun withEmbedded(cid: String, card: JsonObject): JsonObject {
        val data = (card["data"] as? JsonObject).orEmpty()
        val scripts = Scripts.objects(JsonObject(data), Scripts.REGEX_PATH).filterNot { isOurs(it) }
        val g = runCatching { ImageGenStore.load(cid) }.getOrNull()
        val next = if (g != null && hasContent(g)) {
            scripts + Scripts.regex(Scripts.newRegex(), NAME, NEVER, pretty.encodeToString(JsonElement.serializer(), pack(g)), "editdisplay", "g", false, true)
        } else scripts
        return JsonObject(card + ("data" to Fields.set(JsonObject(data), Scripts.REGEX_PATH, JsonArray(next))))
    }

    /** Import: pulls the block regex out of the card. Returns the cleaned card and the image template to restore (or null). */
    fun extract(card: JsonObject): Pair<JsonObject, JsonObject?> {
        val data = (card["data"] as? JsonObject) ?: return card to null
        val scripts = Scripts.objects(data, Scripts.REGEX_PATH)
        val ours = scripts.firstOrNull { isOurs(it) } ?: return card to null
        val packed = runCatching { CardIO.json.parseToJsonElement(CardIO.str(ours, "out")).jsonObject }.getOrNull()
            ?.takeIf { it.s("format") == FORMAT }
        val cleaned = JsonObject(card + ("data" to Fields.set(data, Scripts.REGEX_PATH, JsonArray(scripts.filterNot { isOurs(it) }))))
        if (packed == null) return cleaned to null
        val base = ImageGenStore.blankState()
        val g = JsonObject(base + mapOf(
            "templateName" to JsonPrimitive(packed.s("templateName")),
            "style" to JsonObject(base.o("style").orEmpty() + packed.o("style").orEmpty()),
            "characters" to JsonArray(packed.arr("characters").ifEmpty { base.arr("characters") }),
            "selectedCharacterId" to (packed["selectedCharacterId"]?.takeIf { s -> packed.arr("characters").any { it.s("id") == (s as? JsonPrimitive)?.content } }
                ?: packed.arr("characters").firstOrNull()?.let { JsonPrimitive(it.s("id")) } ?: base["selectedCharacterId"] ?: JsonNull),
            "presets" to JsonArray(packed.arr("presets").map { JsonObject(it + ("images" to JsonArray(emptyList()))) }),
        ))
        return cleaned to g
    }
}
