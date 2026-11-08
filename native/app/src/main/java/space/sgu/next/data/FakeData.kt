package space.sgu.next.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App-wide UI state. */
object AppState {
    var safeMode by mutableStateOf(false)
    var autoCompress by mutableStateOf(true)
}

data class Turn(val id: Int, val command: String, val reply: String, val edited: List<String>)

enum class Activation(val label: String) { Always("상시"), Sub("서브 판단"), Keyword("키워드"), Off("비활성화") }
enum class RefKind(val label: String) { Text("텍스트"), Image("이미지"), Persona("페르소나"), File("파일") }
data class Reference(val id: Int, val name: String, val kind: RefKind, val memo: String, val activation: Activation, val folder: String)

object FakeData {
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
}
