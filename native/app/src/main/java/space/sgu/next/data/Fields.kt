package space.sgu.next.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class FieldKind { Text, LongText, Toggle, TextList, Lorebook, LoreSettings, Regex, Trigger, Onboarding, Bias }

/**
 * Card fields exactly as the original app groups them (module 895 FIELDS), with the Character Card V3
 * path each one is stored at (module 1653 buildCardV3) — so RisuAI reads everything back unchanged.
 * Paths are relative to `card.data`.
 */
data class CardField(
    val id: String,
    val group: String,
    val kind: FieldKind,
    val label: String,
    val path: List<String>,
    val hint: String = "",
)

private fun risu(key: String) = listOf("extensions", "risuai", key)

object Fields {
    val all = listOf(
        // 01 캐릭터
        CardField("characterVersion", "character", FieldKind.Text, "캐릭터 버전", listOf("character_version"), "예: 1.0"),
        CardField("creator", "character", FieldKind.Text, "제작자", listOf("creator"), "카드 제작자 이름 또는 닉네임"),
        CardField("creatorNotes", "character", FieldKind.LongText, "제작자 코멘트", listOf("creator_notes"), "채팅 전에 유저에게 보이는 안내 (사용 팁, 추천 설정, 주의사항). 롤플레이 모델에는 보내지 않아요."),
        CardField("name", "character", FieldKind.Text, "캐릭터 이름", listOf("name"), "닉네임이 없으면 {{char}}로 쓰여요."),
        CardField("nickname", "character", FieldKind.Text, "닉네임", listOf("nickname"), "봇 제목과 캐릭터 이름이 다를 때만. 있으면 {{char}}가 닉네임으로 바뀌어요."),
        CardField("description", "character", FieldKind.LongText, "캐릭터 디스크립션", listOf("description"), "성격, 외모, 배경, 말투, 세계관과 규칙. 카드의 핵심이에요."),
        CardField("firstMessage", "character", FieldKind.LongText, "첫 메세지", listOf("first_mes"), "채팅을 시작할 때 캐릭터가 보내는 첫 메시지"),
        CardField("alternateGreetings", "character", FieldKind.TextList, "추가 첫 메세지", listOf("alternate_greetings")),
        CardField("lorebook", "character", FieldKind.Lorebook, "로어북", listOf("character_book", "entries")),
        CardField("loreSettings", "character", FieldKind.LoreSettings, "로어북 설정", listOf("character_book")),
        // 03 스크립트
        CardField("backgroundHTML", "script", FieldKind.LongText, "백그라운드 임베딩", risu("backgroundHTML"), "채팅 배경에 들어가는 마크다운/HTML ({{bg::에셋}}, {{audio::에셋}} 사용 가능)"),
        CardField("regexScripts", "script", FieldKind.Regex, "정규식 스크립트", risu("customScripts")),
        CardField("triggerScript", "script", FieldKind.Trigger, "트리거 스크립트 (Lua/V2)", risu("triggerscript")),
        CardField("defaultVariables", "script", FieldKind.LongText, "기본 변수", risu("defaultVariables"), "한 줄에 하나씩 이름=값 (예: hp=100)"),
        CardField("customToggles", "script", FieldKind.LongText, "커스텀 토글", risu("toggles"), "RisuAI에서 유저에게 보이는 프롬프트 토글 정의 (한 줄에 하나)"),
        CardField("onboarding", "script", FieldKind.Onboarding, "온보딩", risu("onboarding")),
        CardField("lowLevelAccess", "script", FieldKind.Toggle, "저수준 접근", risu("lowLevelAccess"), "스크립트가 모델과 저장소에 직접 접근 (보안 주의)"),
        // 04 기타
        CardField("bias", "etc", FieldKind.Bias, "Bias", risu("bias")),
        CardField("exampleMessage", "etc", FieldKind.LongText, "예시 대화", listOf("mes_example"), "<START>, {{user}}:, {{char}}: 형식의 예시 대화"),
        CardField("systemPrompt", "etc", FieldKind.LongText, "시스템 프롬프트", listOf("system_prompt"), "설정하면 유저의 메인 프롬프트를 덮어써요."),
        CardField("replaceGlobalNote", "etc", FieldKind.LongText, "글로벌 노트 덮어쓰기", listOf("post_history_instructions"), "설정하면 유저의 글로벌 노트(탈옥)를 대신해요."),
        CardField("additionalText", "etc", FieldKind.LongText, "추가 디스크립션", risu("additionalText"), "디스크립션 뒤에 덧붙는 글"),
        CardField("translatorNote", "etc", FieldKind.LongText, "번역가의 노트", risu("translatorNote"), "번역 모델에게 주는 지시 (용어, 말투)"),
        CardField("hideChatIcon", "etc", FieldKind.Toggle, "아이콘 UI 숨기기", risu("hideChatIcon"), "채팅 화면에서 캐릭터 아이콘을 숨겨요."),
        CardField("utilityBot", "etc", FieldKind.Toggle, "유틸리티 봇", risu("utilityBot"), "페르소나 형식 없는 유틸리티 봇"),
        CardField("escapeOutput", "etc", FieldKind.Toggle, "출력 이스케이프", risu("escapeOutput"), "모델 출력의 HTML을 이스케이프해요."),
    )

    val byId = all.associateBy { it.id }
    fun inGroup(g: String) = all.filter { it.group == g }

    fun label(key: String): String =
        if (key.startsWith("alt:")) "추가 첫 메세지 ${(key.removePrefix("alt:").toIntOrNull() ?: 0) + 1}" else byId[key]?.label ?: key

    // ---------- nested JSON access under card.data ----------

    fun get(data: JsonObject?, path: List<String>): JsonElement? {
        var cur: JsonElement? = data
        for (p in path) cur = (cur as? JsonObject)?.get(p) ?: return null
        return cur
    }

    fun getString(data: JsonObject?, path: List<String>): String = (get(data, path) as? JsonPrimitive)?.contentOrNull ?: ""
    fun getBool(data: JsonObject?, path: List<String>): Boolean = (get(data, path) as? JsonPrimitive)?.booleanOrNull ?: false

    /** Returns a copy of [data] with [value] at [path], creating objects along the way. */
    fun set(data: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        if (path.size == 1) return JsonObject(data + (path[0] to value))
        val child = data[path[0]] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(data + (path[0] to set(child, path.drop(1), value)))
    }

    fun remove(data: JsonObject, path: List<String>): JsonObject {
        if (path.size == 1) return JsonObject(data - path[0])
        val child = data[path[0]] as? JsonObject ?: return data
        return JsonObject(data + (path[0] to remove(child, path.drop(1))))
    }
}
