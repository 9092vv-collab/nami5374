package space.sgu.next.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 앱에 박혀 있던 프롬프트 문구를 한곳에 모음 (설정 › 내장 프롬프트에서 보고 고침).
 * 고친 값은 설정 root["builtinPrompts"][key]에 저장, 비우거나 기본값과 같으면 기본값 사용.
 * 채워 넣는 자리는 [[이름]] (RisuAI의 {{…}}와 겹치지 않게).
 */
object BuiltinPrompts {
    class Item(val key: String, val group: String, val title: String, val where: String, val default: String, val warn: String? = null)

    private const val Q = "\"\"\""

    val items: List<Item> by lazy {
        listOf(
            // ---- 서브 프로세서 (LLM) ----
            Item("sub.system", "서브 판단 (LLM 방식)", "판단 지시문", "명령을 보낼 때마다 서브 모델의 시스템 프롬프트 맨 앞에 들어가요.",
                "You are the planning stage of a RisuAI character-card editing assistant.\n" +
                "First judge the user's intent: is it an instruction/command to work on the character (instruction=true), or ordinary conversation rather than a command (instruction=false)? For ordinary conversation, return empty view and edit lists.\n" +
                "Given the user's command and the list of character fields, decide which fields the writing stage must READ and which it must MODIFY.\n" +
                "Be minimal: request reading only fields the command depends on, and modifying only fields the command asks to change.\n" +
                "Fields that are always visible or never allowed are omitted from the lists below; do not invent field ids."),
            Item("sub.viewHeading", "서브 판단 (LLM 방식)", "읽을 수 있는 항목 목록 제목", "항목 목록 바로 위 한 줄.", "Fields available to READ:"),
            Item("sub.refHeading", "서브 판단 (LLM 방식)", "참고 자료 목록 제목", "참고 자료가 있을 때 그 목록 위 한 줄.", "Reference materials available to READ (use the ref:<id> value in the 'view' array):",
                "ref:<id> 형식 안내를 지우면 서브가 참고 자료를 고르지 못할 수 있어요."),
            Item("sub.editHeading", "서브 판단 (LLM 방식)", "고칠 수 있는 항목 목록 제목", "고칠 수 있는 항목 목록 위 한 줄.", "Fields available to MODIFY:"),
            Item("sub.format", "서브 판단 (LLM 방식)", "답 형식", "시스템 프롬프트 맨 끝. 앱이 이 형식의 JSON을 읽어요.",
                "Respond with JSON: {\"instruction\": boolean, \"task\": string, \"view\": string[], \"edit\": string[], \"reasons\": {fieldId: string}}",
                "키 이름(instruction, view, edit)을 바꾸면 앱이 서브의 답을 읽지 못해요."),
            Item("sub.user", "서브 판단 (공통)", "명령 전달 문장", "서브에게 유저 명령을 넘기는 문장. [[command]] = 유저 명령(+이전 맥락).",
                "User command (the last message is the one to act on; earlier lines are context only): $Q[[command]]$Q"),
            Item("sub.fieldLine", "서브 판단 (공통)", "항목 한 줄", "항목 목록의 각 줄. [[label]] 영어 이름, [[id]] 항목 id, [[description]] 항목 설명, [[state]] 비었는지.",
                "[[label]] ([[id]]): [[description]] Currently [[state]]."),
            Item("sub.stateEmpty", "서브 판단 (공통)", "항목이 비었을 때 표시", "위 [[state]] 자리에 들어가요.", "EMPTY"),
            Item("sub.stateFilled", "서브 판단 (공통)", "항목에 내용이 있을 때 표시", "위 [[state]] 자리에 들어가요.", "has content"),
            // ---- 서브 프로세서 (Jev) ----
            Item("jev.intro", "서브 판단 (Jev 방식)", "상황 설명", "Jev에 넘기는 상황 글의 첫 줄.", "The user is editing a RisuAI character card with an AI assistant."),
            Item("jev.fieldsHeading", "서브 판단 (Jev 방식)", "항목 목록 제목", "", "Character fields:"),
            Item("jev.refsHeading", "서브 판단 (Jev 방식)", "참고 자료 목록 제목", "", "Reference materials:"),
            Item("jev.qIntent", "서브 판단 (Jev 방식)", "질문: 지시인가 잡담인가", "예/아니오로 답하는 질문.",
                "Is the user's intent an instruction or command to create, change or look up the character (not just ordinary conversation, a greeting, a question about the assistant, or small talk)? Answer yes only if the user wants the assistant to do work on the character."),
            Item("jev.qView", "서브 판단 (Jev 방식)", "질문: 이 항목을 읽어야 하나", "[[field]] = 항목 id.",
                "To carry out the user command well, does the assistant need to READ the current content of the field \"[[field]]\"? Answer yes only if the command depends on or should stay consistent with that field."),
            Item("jev.qRef", "서브 판단 (Jev 방식)", "질문: 이 참고 자료를 읽어야 하나", "[[name]] = 참고 자료 이름.",
                "Does the assistant need to READ the reference material \"[[name]]\" to carry out the user command well? Answer yes only if the command clearly benefits from it."),
            Item("jev.qEdit", "서브 판단 (Jev 방식)", "질문: 이 항목을 고쳐야 하나", "[[field]] = 항목 id.",
                "To carry out the user command, should the assistant MODIFY the field \"[[field]]\" (write, change, add to or delete from it)? Answer yes only if the command explicitly or clearly implies changing this field."),
            // ---- 세션 요약 ----
            Item("summary.system", "세션 요약", "요약 지시문", "대화가 길어져 이전 턴을 요약할 때의 시스템 프롬프트.",
                "You keep a running summary of a session in which a user edits a RisuAI character card with an AI assistant.\n" +
                "Merge the previous summary (if any) with the new turns into ONE short summary.\n" +
                "Keep only what later turns need: the user's goals, decisions, preferences and constraints, and which fields were changed and how.\n" +
                "Do not copy field contents (the card itself is always sent separately). Drop small talk.\n" +
                "At most 10 short bullet points, under 1,200 characters. Write in the language the user writes in. Output only the summary."),
            Item("summary.previous", "세션 요약", "이전 요약 머리말", "이전 요약이 있을 때. [[summary]] = 이전 요약.", "Previous summary:\n[[summary]]\n\n"),
            Item("summary.turns", "세션 요약", "새 턴 머리말", "[[turns]] = 요약할 턴 목록.", "New turns:\n[[turns]]"),
            // ---- 메인 프로세서 ----
            Item("main.outputFormat", "메인 프로세서", "출력 형식 내장 규칙", "프리셋의 '출력 형식' 블록이 비어 있을 때 대신 들어가요.", SguData.OUTPUT_FORMAT_TEXT,
                "JSON 모양(message, edits, field, op …)을 바꾸면 앱이 답을 읽지 못해서 편집이 적용되지 않아요."),
            Item("main.chatNote", "메인 프로세서", "잡담 안내문", "서브가 '지시가 아니라 대화'라고 판단하면 유저 명령 뒤에 붙어요.",
                "[Note: the user is making ordinary conversation, not giving an instruction. Reply naturally in \"message\" and return an empty \"edits\" array.]"),
            Item("main.noFields", "메인 프로세서", "보이는 항목이 없을 때", "'캐릭터 데이터' 블록에 넣을 항목이 하나도 없을 때.", "(no fields are visible this turn)"),
            Item("main.emptyValue", "메인 프로세서", "항목 값이 비었을 때", "'캐릭터 데이터' 블록에서 빈 항목의 값 자리.", "(empty)"),
            Item("main.historySummary", "메인 프로세서", "세션 기록: 요약 머리말", "'세션 기록' 블록. [[summary]] = 이전 턴 요약.", "Summary of earlier turns in this session:\n[[summary]]"),
            Item("main.historyRecent", "메인 프로세서", "세션 기록: 최근 턴 머리말", "요약 뒤에 최근 턴이 이어질 때. [[turns]] = 최근 턴 목록.", "Recent turns:\n[[turns]]"),
            Item("main.turnLine", "메인 프로세서", "턴 한 줄", "세션 기록과 세션 요약에서 턴 하나. [[n]] 번호, [[command]] 유저 명령, [[response]] 답 요약, [[edits]] 고친 항목.",
                "[[n]]. User: [[command]]\n   Result: [[response]] [[[edits]]]"),
            Item("main.noEdits", "메인 프로세서", "고친 항목이 없을 때", "위 [[edits]] 자리.", "no edits"),
            Item("main.refPersona", "메인 프로세서", "참고 자료: 페르소나 제목", "[[name]] = 페르소나 이름.", "### Persona of {{user}} ([[name]]) — information about the user, who is {{user}}"),
            Item("main.refNote", "메인 프로세서", "참고 자료: 메모 줄", "[[note]] = 메모.", "Note: [[note]]"),
            Item("main.refImage", "메인 프로세서", "참고 자료: 이미지 안내", "이미지 참고 자료가 있을 때 본문 자리에 들어가요.", "(Image attached to the user's message below.)"),
            Item("main.personaImage", "메인 프로세서", "페르소나 이미지 이름표", "[[name]] = 페르소나 이름.", "persona image of {{user}} ([[name]])"),
            Item("main.imageLabel", "메인 프로세서", "첨부 이미지 표시 (이름 있음)", "[[label]] = 이미지 이름.", "[Attached image: [[label]]]"),
            Item("main.image", "메인 프로세서", "첨부 이미지 표시", "유저가 붙인 이미지 앞.", "[Attached image]"),
            Item("main.imagesMsg", "메인 프로세서", "이미지 묶음 안내", "유저 명령 블록의 역할이 user가 아닐 때 이미지를 따로 보내는 메시지.", "(Images attached by the user)"),
            Item("main.prefillContinue", "메인 프로세서", "프리필 뒤에 붙이는 유저 메시지", "프리필을 받지 않는 모델(Claude 4.6 이후 등)에 보낼 때, 프리필 글 뒤에 이 한 줄을 유저 메시지로 붙여요.",
                "(Continue from exactly where the assistant message above left off.)"),
            // ---- 웹 검색 ----
            Item("web.results", "웹 검색", "검색 결과 머리말", "[[query]] = 검색어, [[results]] = 결과 목록.",
                "Web search results for \"[[query]]\" (retrieved just now). Use them when relevant and mention the source URL when you rely on one.\n\n[[results]]"),
            Item("web.empty", "웹 검색", "결과가 없을 때", "[[query]] = 검색어.",
                "Web search for \"[[query]]\" returned no results (the search service may be blocked or unavailable). Answer from your own knowledge and say that search found nothing."),
            Item("web.failed", "웹 검색", "검색이 실패했을 때", "[[query]] = 검색어, [[error]] = 오류 내용.",
                "Web search for \"[[query]]\" failed ([[error]]). Tell the user that web search is unavailable right now and answer from your own knowledge."),
            // ---- 공통 ----
            Item("chat.jsonOnly", "공통", "JSON으로만 답하라는 문구", "JSON 형식 지정을 직접 지원하지 않는 방식(Anthropic 형식)에서 마지막 유저 메시지에 붙어요. [[schema]] = JSON 스키마.",
                "Respond with a single JSON object only (no prose, no code fences) matching this JSON schema:\n[[schema]]",
                "지우면 모델이 JSON이 아닌 글로 답해서 앱이 읽지 못할 수 있어요."),
        ) + WFields.all.map { Item("label." + it.id, "항목 영어 이름", it.label, "AI에게 '${it.label}' 항목을 알려줄 때 쓰는 이름 (id: ${it.id}).", it.aiLabel) }
    }

    private val byKey by lazy { items.associateBy { it.key } }

    fun default(key: String): String = byKey[key]?.default ?: ""
    fun overridden(key: String): Boolean = SettingsStore.root.o("builtinPrompts").s(key).let { it.isNotBlank() && it != default(key) }
    fun get(key: String): String = SettingsStore.root.o("builtinPrompts").s(key).ifBlank { default(key) }

    /** [[이름]] 자리를 채워서 돌려줌. */
    fun t(key: String, vararg pairs: Pair<String, String>): String {
        var s = get(key)
        for ((k, v) in pairs) s = s.replace("[[$k]]", v)
        return s
    }

    fun set(key: String, value: String) = SettingsStore.update { m ->
        val cur = (m["builtinPrompts"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (value.isBlank() || value == default(key)) cur.remove(key) else cur[key] = JsonPrimitive(value)
        m["builtinPrompts"] = JsonObject(cur)
    }

    fun label(id: String): String = get("label.$id")
}
