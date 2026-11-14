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
        ) + WFields.all.map { Item("label." + it.id, "항목 영어 이름", it.label, "AI에게 '${it.label}' 항목을 알려줄 때 쓰는 이름 (id: ${it.id}).", it.aiLabel) } + extraItems()
    }

    private val kindNames = mapOf(
        "text" to "텍스트 항목", "toggle" to "스위치 항목", "textList" to "목록(첫 메시지 등)", "lorebook" to "로어북", "regexList" to "정규식 스크립트",
        "trigger" to "트리거 스크립트", "biasList" to "Bias", "loreSettings" to "로어북 설정", "onboarding" to "온보딩",
        "genFree" to "캐릭터 자유 프롬프트", "genFreeRun" to "자유 프롬프트 실행", "genCompress" to "이미지 압축", "genTemplate" to "프리셋 템플릿", "genCharacters" to "이미지 캐릭터 설정",
    )

    /** 예전에는 코드에 박혀 있던 것들: AI가 보는 편집 방법(op) 설명, JSON 스키마 설명, 참고 자료 한 줄, 모델 테스트 문장. */
    private fun extraItems(): List<Item> {
        val g = "편집 방법 (AI가 보는 값 형식)"
        val warnOp = "op 이름(set, add …)은 앱이 알아보는 이름이라 그대로 두고, 값이 어떤 모양인지 설명하는 글만 고치세요. 모양을 바꾸면 앱이 읽지 못해 편집이 거부돼요."
        val ops = listOf("text", "toggle", "textList", "lorebook", "regexList", "trigger", "biasList", "loreSettings", "onboarding").flatMap { k ->
            SguData.defaultOpsFor(k).map { (op, v) -> Item("op.$k.$op", g, "${kindNames[k]} · $op", "AI가 '${kindNames[k]}'을(를) 고칠 때 op \"$op\"의 값(value) 설명으로 보여요.", v, warnOp) }
        } + GenOps.OP_KEYS.flatMap { k ->
            GenOps.defaultOps(k).map { (op, v) -> Item("op.$k.$op", g, "${kindNames[k]} · $op", "AI가 '${kindNames[k]}'을(를) 고칠 때 op \"$op\"의 값(value) 설명으로 보여요.", v, warnOp) }
        }
        val gs = "JSON 형식 설명 (API에 같이 보내는 스키마)"
        val warnS = "칸의 이름이 아니라 설명 글만 고치는 곳이에요."
        return ops + listOf(
            Item("schema.message", gs, "메인 답: message 칸", "메인 프로세서 답의 message 칸 설명.", "Short summary for the user, in the user's language.", warnS),
            Item("schema.value", gs, "메인 답: value 칸", "메인 프로세서 답의 편집 value 칸 설명.", "New value. Type depends on the field and op.", warnS),
            Item("schema.sub.instruction", gs, "서브 답: instruction 칸", "서브 프로세서(LLM 방식) 답의 instruction 칸 설명.", "true if the user's intent is an instruction/command for the assistant to work on the character; false if it is ordinary conversation.", warnS),
            Item("schema.sub.task", gs, "서브 답: task 칸", "", "One-sentence restatement of what the main processor must do.", warnS),
            Item("schema.sub.view", gs, "서브 답: view 칸", "", "Field ids whose current content must be read.", warnS),
            Item("schema.sub.edit", gs, "서브 답: edit 칸", "", "Field ids that must be modified.", warnS),
            Item("schema.sub.reasons", gs, "서브 답: reasons 칸", "", "Optional short reason per field id.", warnS),
            Item("ref.line", "참고 자료 설명", "서브에게 보이는 참고 자료 한 줄", "서브 판단 때 참고 자료 목록의 각 줄. [[id]] 참고 자료 id, [[name]] 이름, [[memo]] 메모 부분, [[bits]] 종류·메모·앞부분.",
                "ref:[[id]] — name: \"[[name]]\"; memo: [[memo]] ([[bits]])", "ref:[[id]] 부분을 지우면 서브가 참고 자료를 고르지 못해요."),
            Item("ref.memo", "참고 자료 설명", "메모가 있을 때 [[memo]] 자리", "[[note]] = 메모 내용.", "\"[[note]]\""),
            Item("ref.memoNone", "참고 자료 설명", "메모가 없을 때 [[memo]] 자리", "", "(none)"),
            Item("ref.starts", "참고 자료 설명", "글 자료의 앞부분 표시", "텍스트·페르소나 자료의 시작 160자. [[text]] = 앞부분.", "starts with: \"[[text]]\""),
            Item("guide.image", "이미지 작성 규칙", "이미지 탭 작성 규칙 (그림체 · 캐릭터 블록 · 프리셋 템플릿의 경계)",
                "AI가 프리셋 템플릿·이미지 캐릭터 설정·자유 프롬프트 중 하나라도 고칠 수 있는 턴에, 편집 방법 설명 바로 뒤에 붙어요. 나만의 규칙은 여기에 더하세요.",
                "## Image prompt rules (SGU image tab)\n" +
                "The app builds the final NovelAI prompt from three layers. Each kind of tag has exactly one home; never repeat a tag in another layer.\n" +
                "\n" +
                "1. Art style (그림체 설정) — you cannot see or edit it. It already adds the quality tags, the art-style/artist tags and the general-purpose negative prompt to EVERY image. So NEVER write quality tags (masterpiece, best quality, very aesthetic, absurdres, highres ...), art-style or artist tags, or general-purpose negatives (lowres, worst quality, bad anatomy, bad hands, jpeg artifacts, watermark, text ...) anywhere: not in character blocks, free prompts, presets, or any negative field.\n" +
                "2. Character blocks (genCharacters) — APPEARANCE ONLY: gender, hair, face, eyes, body, and outfit or state variants as separate blocks/folders. No pose, action, expression, camera or background here.\n" +
                "3. Preset templates (genTemplate) — ACTION ONLY: pose, gesture, action, expression, camera angle/framing, scene/background, interaction between characters. A preset NEVER describes appearance (no hair, eyes, body, outfit tags in assetPrompt or chars[].prompt).\n" +
                "   - Appearance enters a preset only by attaching a character (chars[].characterId) and listing that character's EXISTING blocks in chars[].blockIds. Choose the blocks that fit the scene; when unsure, use the character's basic appearance blocks and leave optional variant blocks/folders out.\n" +
                "   - assetPrompt = what is shared by the whole picture (head count such as 1girl / 2girls / solo unless a character block already carries it, composition, background). chars[].prompt = only that character's own pose/action/expression.\n" +
                "   - Negative fields of a preset hold only what is specific to that pose (e.g. a wrong pose to avoid), never general negatives.\n" +
                "   - If the scene needs an appearance block that does not exist (e.g. an outfit), do NOT write that appearance into the preset and do NOT create the block yourself: finish the preset with the existing blocks and tell the user in \"message\" which block is missing.\n" +
                "\n" +
                "Protecting the user's existing work:\n" +
                "- \"Make a template / preset\" means genTemplate ONLY. While making or editing presets, do not add, change, rename, move or remove character blocks or folders, and do not touch free prompts, unless the user explicitly asked for that.\n" +
                "- You cannot restore overwritten text (the session history keeps no previous text). Never rewrite the text of an existing block or an existing preset unless the user explicitly told you to change that specific one. When in doubt, add a new one instead of overwriting, or ask in \"message\".\n" +
                "- The free prompt (genFree) follows the same rules: no quality/style tags, no general negatives."),
            Item("test.prompt", "모델 테스트", "모델 테스트 문장", "설정 › 모델에서 '테스트'를 누르면 모델에 이 문장을 보내요."  , "Reply with the single word: OK"),
        )
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
