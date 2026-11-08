package space.sgu.next.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val label: String) { System("기기 설정"), Light("라이트"), Dark("다크") }

/** App-wide UI state for the skeleton. Real storage comes in step 3. */
object AppState {
    private const val PREFS = "sgu"
    var themeMode by mutableStateOf(ThemeMode.System)
        private set
    var safeMode by mutableStateOf(false)
    var autoCompress by mutableStateOf(true)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        themeMode = runCatching { ThemeMode.valueOf(p.getString("theme", "System")!!) }.getOrDefault(ThemeMode.System)
    }

    fun setTheme(ctx: Context, mode: ThemeMode) {
        themeMode = mode
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", mode.name).apply()
    }
}

data class Character(
    val id: Int,
    val name: String,
    val creator: String,
    val folder: String?,
    val tagline: String,
    val updated: String,
    val hue: Float,
)

data class Turn(val id: Int, val command: String, val reply: String, val edited: List<String>)

enum class Activation(val label: String) { Always("상시"), Sub("서브 판단"), Keyword("키워드"), Off("비활성화") }
enum class RefKind(val label: String) { Text("텍스트"), Image("이미지"), Persona("페르소나"), File("파일") }
data class Reference(val id: Int, val name: String, val kind: RefKind, val memo: String, val activation: Activation, val folder: String)

object FakeData {
    private val names = listOf(
        "엘라리온", "한서윤", "카이토", "미르", "세라핀", "도윤", "루미나", "백야", "아스트라", "하람",
        "노아", "벨벳", "이든", "소린", "유나", "레이븐", "다온", "에코", "시엘", "무진",
    )
    private val taglines = listOf(
        "냉소적인 엘프 궁수", "비 오는 날의 카페 사장", "기억을 잃은 우주 비행사", "골목 끝 서점 주인",
        "왕국 최후의 마법사", "말수 적은 고등학교 선배", "도시 전설을 쫓는 기자", "시간을 되감는 탐정",
    )
    private val folders = listOf(null, "판타지", "현대", "판타지", null, "SF")

    val characters = mutableStateListOf<Character>().apply {
        for (i in 0 until 40) {
            add(
                Character(
                    id = i + 1,
                    name = names[i % names.size] + if (i >= names.size) " ${i / names.size + 1}" else "",
                    creator = if (i % 4 == 0) "" else "나미",
                    folder = folders[i % folders.size],
                    tagline = taglines[i % taglines.size],
                    updated = "${(i % 9) + 1}일 전",
                    hue = (i * 37 % 360).toFloat(),
                )
            )
        }
    }

    fun character(id: Int) = characters.firstOrNull { it.id == id }

    val turns = listOf(
        Turn(1, "냉소적인 엘프 궁수 캐릭터를 만들어줘. 디스크립션과 첫 메시지를 써줘",
            "디스크립션과 첫 메시지를 작성했어요. 숲의 경계를 지키는 궁수로, 인간을 믿지 않지만 약속은 반드시 지키는 성격으로 잡았어요.",
            listOf("디스크립션", "첫 메시지")),
        Turn(2, "말투를 조금 더 건조하게, 반말로 바꿔줘",
            "대사를 짧고 건조한 반말로 다듬었어요. 감정 표현은 행동 묘사로 옮겼어요.",
            listOf("첫 메시지", "예시 대화")),
        Turn(3, "로어북에 엘프 숲 설정 3개 추가해줘",
            "로어북에 '은빛 숲', '경계의 서약', '사라진 왕가' 항목을 추가했어요.",
            listOf("로어북")),
        Turn(4, "추가 첫 메시지 2개 더 써줘. 하나는 비 오는 밤 상황으로",
            "추가 첫 메시지 2개를 썼어요. 두 번째는 비 오는 밤, 화살에 맞은 유저를 발견하는 장면이에요.",
            listOf("추가 첫 메시지")),
    )

    val references = listOf(
        Reference(1, "세계관 설정", RefKind.Text, "대륙 지도와 국가 관계", Activation.Always, "공통"),
        Reference(2, "말투 참고", RefKind.Text, "건조한 반말 예시 모음", Activation.Sub, "공통"),
        Reference(3, "내 페르소나", RefKind.Persona, "유저 기본 설정", Activation.Always, "공통"),
        Reference(4, "엘프 의상", RefKind.Image, "그림체 참고용", Activation.Keyword, "판타지"),
        Reference(5, "마법 체계", RefKind.Text, "원소 마법 규칙", Activation.Keyword, "판타지"),
        Reference(6, "종족 표", RefKind.File, "CSV 표", Activation.Off, "판타지"),
        Reference(7, "서울 지명", RefKind.Text, "현대물 배경 장소", Activation.Sub, "현대"),
        Reference(8, "카페 메뉴", RefKind.Text, "가게 메뉴판", Activation.Keyword, "현대"),
    )

    val fields = listOf(
        "이름" to "엘라리온",
        "디스크립션" to "은빛 숲의 경계를 지키는 엘프 궁수. 300년을 살았지만 인간을 가까이 둔 적은 없다. 말수가 적고, 약속은 목숨처럼 지킨다.",
        "성격" to "냉소적, 과묵함, 의리 있음, 은근히 다정함",
        "시나리오" to "국경 순찰 중 다친 {{user}}를 발견한다.",
        "첫 메시지" to "\"…움직이지 마. 화살촉이 아직 박혀 있어.\" 그녀는 활을 내려놓지 않은 채 다가왔다.",
        "예시 대화" to "{{user}}: 고마워.\n{{char}}: 착각하지 마. 네가 여기서 죽으면 시끄러워질 뿐이야.",
    )
}
