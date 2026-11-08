package space.sgu.next.data

/** Provider presets, ported from the original app (module 947). */
data class Preset(
    val id: String,
    val name: String,
    val format: String,
    val baseUrl: String,
    val auth: String,
    val description: String,
    val models: List<String>,
    val editableUrl: Boolean,
    val keyPlaceholder: String,
    val notes: List<String>,
    val cacheNote: String,
    val defaultCache: String,
    val hidden: Boolean = false,
    val jev: Boolean = false,
    val image: Boolean = false,
)

object Presets {
    val all = listOf(
        Preset("openai", "OpenAI", "openai-chat", "https://api.openai.com/v1", "bearer",
            "OpenAI Chat Completions. Responses API가 필요하면 포맷을 바꾸세요.",
            listOf("gpt-5", "gpt-5-mini", "gpt-4.1", "gpt-4o"), false, "sk-...",
            listOf(
                "Chat Completions에서는 max_tokens 대신 max_completion_tokens를 사용합니다(기본값). 호환 서버를 쓰면 아래 토글로 max_tokens로 바꿀 수 있습니다.",
                "추론(reasoning) 모델은 temperature·top_p 등을 받지 않을 수 있습니다. 오류가 나면 모델 설정에서 해당 값을 비워 두세요.",
                "reasoning_effort(low/medium/high)를 모델 설정의 '추론'에서 지정합니다.",
            ),
            "자동 캐싱입니다. 프롬프트 앞부분 1,024토큰 이상이 동일하면 캐시 읽기 요금(약 10%)이 적용됩니다.", "auto"),
        Preset("anthropic", "Anthropic", "anthropic", "https://api.anthropic.com/v1", "x-api-key",
            "Claude Messages API.", listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5"), false, "sk-ant-...",
            listOf(
                "x-api-key와 anthropic-version 헤더를 사용합니다.",
                "max_tokens가 필수이며(미지정 시 4096), 확장 사고를 켜면 budget보다 커야 합니다.",
                "sampling 값(temperature/top_p)을 동시에 보내면 일부 모델이 거부합니다.",
            ),
            "명시적 캐싱입니다. cache_control 지점(최대 4개)까지가 캐시됩니다. 쓰기 1.25배(5분) / 2배(1시간), 읽기 0.1배.", "auto"),
        Preset("google", "Google AI Studio", "gemini", "https://generativelanguage.googleapis.com/v1beta", "x-goog-api-key",
            "Gemini API (AI Studio 키).", listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite"), false, "AIza...",
            listOf("API 키는 x-goog-api-key 헤더로 보냅니다.", "maxOutputTokens, thinkingBudget을 모델 설정에서 지정합니다."),
            "암시적 캐싱이 기본으로 켜져 있어 별도 설정이 필요 없습니다(적중 시 90% 할인, 최대 24시간).", "auto"),
        Preset("vertex", "Vertex AI", "gemini", "https://aiplatform.googleapis.com/v1/publishers/google", "vertex-sa",
            "Google Cloud Vertex AI. Project ID · Client Email · Private Key(서비스 계정)로 인증합니다.",
            listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite"), false, "-----BEGIN PRIVATE KEY-----",
            listOf(
                "API 키 대신 서비스 계정을 사용합니다: Project ID, Client Email, Private Key 3가지(또는 서비스 계정 JSON 붙여넣기).",
                "앱이 Private Key로 JWT(RS256)를 서명해 OAuth 액세스 토큰을 발급받고 1시간마다 갱신합니다. 키는 기기 보안 저장소에만 보관됩니다.",
                "지역(Location)은 global이 기본입니다. 모델에 따라 us-central1 등 지역 엔드포인트가 필요할 수 있습니다.",
                "서비스 계정에 'Vertex AI User' 역할이 있어야 하며 프로젝트에서 Vertex AI API가 켜져 있어야 합니다.",
            ),
            "Gemini와 같은 암시적 캐싱이 기본으로 켜져 있습니다(90% 할인, 최대 24시간).", "auto"),
        Preset("copilot", "GitHub Copilot", "openai-chat", "https://api.individual.githubcopilot.com", "copilot-oauth",
            "GitHub Copilot 구독으로 모델을 사용합니다. GitHub 장치 코드 로그인(OAuth)으로 토큰을 받습니다.",
            listOf("gpt-4.1", "gpt-5-mini", "claude-sonnet-4.5", "gemini-2.5-pro"), false, "GitHub OAuth 토큰(로그인하면 자동 입력)",
            listOf(
                "'GitHub 로그인'으로 받은 OAuth 토큰만 기기 보안 저장소에 저장됩니다.",
                "SGU Next에서는 GitHub 로그인이 아직 준비 중이에요. 기존 앱 설정 백업(키 포함)을 가져오면 토큰이 함께 옮겨져요.",
            ),
            "Copilot은 캐싱 파라미터를 공개하지 않으므로 앱은 캐싱 설정을 보내지 않습니다.", "off"),
        Preset("vertex-express", "Vertex AI (Express)", "gemini", "https://aiplatform.googleapis.com/v1/publishers/google", "query-key",
            "Vertex AI Express 모드 API 키.", listOf("gemini-2.5-pro", "gemini-2.5-flash"), true, "Express API key",
            listOf("Express 모드 API 키는 일반 generateContent 경로에서 거부될 수 있습니다. 'Vertex AI' 프리셋(서비스 계정)을 권장합니다."),
            "Gemini와 동일한 암시적 캐싱.", "off", hidden = true),
        Preset("openrouter", "OpenRouter", "openai-chat", "https://openrouter.ai/api/v1", "bearer",
            "OpenRouter를 통해 여러 모델 사용. 모델 목록 불러오기 지원.",
            listOf("anthropic/claude-sonnet-4.5", "openai/gpt-5", "google/gemini-2.5-pro", "deepseek/deepseek-chat-v3.1"), false, "sk-or-...",
            listOf("모델 ID는 'vendor/model' 형식입니다.", "추가 바디 필드에 provider 라우팅({\"provider\":{\"order\":[...]}})을 넣을 수 있습니다."),
            "모델에 따라 다릅니다. Anthropic·Gemini 계열은 cache_control 지점이 필요하고, OpenAI·DeepSeek·Grok 계열은 자동 캐싱입니다.", "auto"),
        Preset("novelai", "NovelAI", "openai-chat", "https://text.novelai.net/oa/v1", "bearer",
            "NovelAI OpenAI 호환 엔드포인트 (Persistent API token).", listOf("glm-4-6", "glm-4-5"), true, "pst-...",
            listOf("GLM 계열 텍스트 모델을 OpenAI 호환 엔드포인트로 사용합니다. Persistent API Token(pst-…)이 필요합니다."),
            "캐싱을 지원하지 않습니다(설정 무시).", "off"),
        Preset("bedrock-mantle", "AWS Bedrock (Mantle)", "openai-chat", "https://bedrock-mantle.us-east-1.api.aws/v1", "bearer",
            "Bedrock OpenAI 호환 엔드포인트. Bedrock API 키를 Bearer로 사용합니다. 리전에 맞게 URL을 수정하세요.",
            listOf("anthropic.claude-sonnet-4-5", "amazon.nova-pro-v1:0"), true, "Bedrock API key",
            listOf("Bedrock OpenAI 호환(Mantle) 엔드포인트입니다. 리전에 맞게 Base URL을 수정하세요."),
            "OpenAI 계열 모델은 자동 캐싱입니다.", "off"),
        Preset("jev", "Jev (TypeSafe AI)", "jev", "https://api.typesafe.ai/v1", "bearer",
            "System One 모델. 서브 프로세서 전용(열람/편집 필요 여부 판정).", listOf("jev-latest"), false, "TypeSafe API key",
            listOf("서브 프로세서 전용입니다. 예/아니오 판정만 수행합니다.", "설정 › 프로세서에서 '서브 = Jev'를 선택하고 이 프로바이더를 지정하세요."),
            "서브 프로세서 요청은 항상 캐싱을 쓰지 않습니다.", "off", jev = true),
        Preset("novelai-image", "NovelAI (image)", "novelai-image", "https://image.novelai.net", "bearer",
            "NovelAI 이미지 생성 (Persistent API Token). 이미지 탭 › 이미지 생성에서 사용합니다.", emptyList(), false, "pst-...",
            listOf("모델 등록이 필요 없습니다. Persistent API Token을 연결하세요.", "이미지 탭 › 이미지 생성에서 사용합니다."),
            "해당 없음.", "off", image = true),
        Preset("custom", "커스텀 (OpenAI 호환)", "openai-chat", "https://example.com/v1", "bearer",
            "Groq, DeepSeek, xAI, Mistral, 로컬 서버 등 OpenAI 호환 엔드포인트. 포맷·인증 방식을 바꿀 수 있습니다.",
            emptyList(), true, "API key",
            listOf(
                "Groq, DeepSeek, xAI, Mistral, 로컬 서버(Ollama, LM Studio 등)를 연결할 수 있습니다.",
                "호환 서버가 max_completion_tokens를 모르면 토글을 max_tokens로 두세요(기본값).",
                "서버별 추가 파라미터는 '추가 바디 필드'에 JSON으로 넣습니다.",
            ),
            "서버마다 다릅니다. 모르면 '끔'으로 두세요.", "off"),
    )

    val byId = all.associateBy { it.id }

    val formatLabels = linkedMapOf(
        "openai-chat" to "OpenAI Chat Completions",
        "openai-responses" to "OpenAI Responses",
        "anthropic" to "Anthropic Messages",
        "gemini" to "Google Gemini",
        "jev" to "Jev System One",
        "novelai-image" to "NovelAI Image",
    )

    val authLabels = linkedMapOf(
        "bearer" to "Authorization: Bearer",
        "x-api-key" to "x-api-key 헤더",
        "x-goog-api-key" to "x-goog-api-key 헤더",
        "query-key" to "?key= 쿼리",
        "vertex-sa" to "서비스 계정 (OAuth)",
        "copilot-oauth" to "GitHub Copilot (OAuth)",
        "none" to "없음",
    )

    private val visionYes = Regex("gpt-4o|gpt-4\\.1|gpt-4\\.5|gpt-4-turbo|gpt-4-vision|gpt-5|chatgpt-4o|\\bo[134](-|$)|claude|gemini|gemma-3|pixtral|llava|vision|\\bvl\\b|-vl|qwen.*vl|qwen3-(omni|vl)|internvl|minicpm-v|grok-(2-vision|4|vision)|llama-?(3\\.2-(11|90)b|4)|mistral-(medium|small)-(3|latest)|kimi-(k2\\.5|vl)|glm-4\\.?\\d*v|doubao.*vision|step-1v", RegexOption.IGNORE_CASE)
    private val visionNo = Regex("embed|whisper|tts|dall-e|moderation|rerank|gpt-3\\.5|instruct$|-coder-(?!.*vl)|codestral|deepseek-(chat|reasoner|r1|v3)(?!.*vl)", RegexOption.IGNORE_CASE)

    /** Same rule as the original (module 883). */
    fun guessVision(modelId: String) = visionYes.containsMatchIn(modelId) && !visionNo.containsMatchIn(modelId)
}
