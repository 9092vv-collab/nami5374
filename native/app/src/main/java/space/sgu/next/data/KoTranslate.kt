package space.sgu.next.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 임시 번역 (영어 → 한국어, 화면 표시용). 원문은 건드리지 않고 번역문만 따로 보관.
 * 폰 안에서 도는 ML Kit 번역 — 처음 한 번 번역 데이터를 내려받음.
 * {{…}} 명령어와 <태그>는 번역하지 않고 그대로 둠.
 */
object KoTranslate {
    var on by mutableStateOf(false)
    /** "", "번역 데이터 받는 중…", 또는 오류 문구 */
    var status by mutableStateOf("")
    val cache = mutableStateMapOf<String, String>()

    private val client by lazy {
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.KOREAN).build())
    }
    private val lock = Mutex()
    private var ready = false

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    private suspend fun ensure() = lock.withLock {
        if (ready) return@withLock
        status = "번역 데이터 받는 중… (처음 한 번, 30MB쯤)"
        try { client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await(); ready = true; status = "" }
        catch (e: Exception) { status = "번역 데이터를 받지 못했어요. 인터넷 연결을 확인해 주세요. (${e.message})"; throw e }
    }

    /** 글 조각으로 나눔: true = 번역하지 않는 조각({{…}}, <태그>, 줄바꿈) */
    fun split(text: String): List<Pair<Boolean, String>> {
        val out = mutableListOf<Pair<Boolean, String>>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out.add(false to sb.toString()); sb.clear() } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{' && i + 1 < text.length && text[i + 1] == '{') {
                var depth = 0; var j = i
                while (j < text.length - 1) {
                    if (text[j] == '{' && text[j + 1] == '{') { depth++; j += 2 }
                    else if (text[j] == '}' && text[j + 1] == '}') { depth--; j += 2; if (depth == 0) break }
                    else j++
                }
                if (depth == 0) { flush(); out.add(true to text.substring(i, j)); i = j; continue }
            }
            if (c == '<' && i + 1 < text.length && (text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '!')) {
                val e = text.indexOf('>', i)
                if (e != -1 && e - i <= 80 && '\n' !in text.substring(i, e)) { flush(); out.add(true to text.substring(i, e + 1)); i = e + 1; continue }
            }
            if (c == '\n') { flush(); out.add(true to "\n"); i++; continue }
            sb.append(c); i++
        }
        flush()
        return out
    }

    private val latin = Regex("[A-Za-z]{2,}")

    suspend fun translate(text: String): String {
        cache[text]?.let { return it }
        ensure()
        val sb = StringBuilder()
        for ((keep, piece) in split(text)) {
            if (keep || !latin.containsMatchIn(piece)) { sb.append(piece); continue }
            val lead = piece.takeWhile { it.isWhitespace() }
            val trail = piece.takeLastWhile { it.isWhitespace() }
            val core = piece.trim()
            sb.append(lead).append(runCatching { client.translate(core).await() }.getOrDefault(core)).append(trail)
        }
        return sb.toString().also { cache[text] = it }
    }
}
