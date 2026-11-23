package space.sgu.next

/** 안드로이드 R 흉내 (글꼴만). */
object R {
    object font {
        const val pretendardgov_regular = 1
        const val pretendardgov_medium = 2
        const val pretendardgov_semibold = 3
        const val pretendardgov_bold = 4
        const val pretendardgov_extrabold = 5
        fun path(id: Int) = "font/" + when (id) {
            2 -> "pretendardgov_medium"; 3 -> "pretendardgov_semibold"; 4 -> "pretendardgov_bold"; 5 -> "pretendardgov_extrabold"; else -> "pretendardgov_regular"
        } + ".otf"
    }
}
