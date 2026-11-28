package space.sgu.next.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 그림체 연구: 작가 태그마다 0.2~1.0(0.05 단위) 가중치를 랜덤으로 붙여 한 장씩 뽑아 봄.
 * 열려 있는 이미지 캐릭터(켜진 블록 + 자유 프롬프트) 기준. 결과는 지우기 전까지 남음 (gen/research.json).
 */
object StyleResearch {
    data class Run(val done: Int, val total: Int)
    val progress = mutableStateMapOf<String, Run>()
    val error = mutableStateMapOf<String, String>()
    var version by mutableIntStateOf(0)
        private set
    const val MAX = 100
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<String, Job>()

    private fun file(cid: String) = File(ImageGenStore.genDir(cid), "research.json")
    fun load(cid: String): JsonObject = runCatching { CardIO.json.parseToJsonElement(file(cid).readText()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    private fun save(cid: String, o: JsonObject) {
        file(cid).writeText(CardIO.json.encodeToString(JsonElement.serializer(), o)); version++
    }
    fun setArtists(cid: String, text: String) { val o = load(cid); if (o.s("artists") != text) save(cid, o.with("artists" to JsonPrimitive(text))) }

    /** 쉼표·줄바꿈으로 나눔. 이미 붙은 가중치(0.6::…::)는 벗기고, artist: 가 없으면 붙임. */
    fun artists(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { t ->
        val bare = Regex("""^-?[\d.]+::(.*?)(::)?$""").find(t)?.groupValues?.get(1)?.trim() ?: t
        if (bare.startsWith("artist:", true)) bare else "artist:$bare"
    }.distinct()

    private val weights = (4..20).map { it * 5 }   // 20..100 (%)
    private fun fmt(p: Int): String = if (p == 100) "1" else "0." + (if (p % 10 == 0) "${p / 10}" else "%02d".format(p))
    fun combo(artists: List<String>): String = artists.joinToString(", ") { "${fmt(weights.random())}::$it::" }

    fun running(cid: String) = progress.containsKey(cid)

    fun start(cid: String, charId: String, artistText: String, n: Int) {
        if (running(cid)) return
        val list = artists(artistText)
        if (list.isEmpty()) { error[cid] = "작가 태그를 입력하세요."; return }
        if (GenRun.running(cid)) { error[cid] = "다른 이미지 생성이 진행 중이에요. 끝난 뒤에 시작하세요."; return }
        setArtists(cid, artistText)
        error.remove(cid)
        val total = n.coerceIn(1, MAX)
        progress[cid] = Run(0, total)
        jobs[cid] = scope.launch {
            try {
                // 시드 고정: 이번 연구 내내 같은 시드 → 작가 가중치만 달라짐
                val seed = ImageGenStore.seedFor(cid)
                for (i in 0 until total) {
                    if (!isActive) break
                    val g = ImageGenStore.load(cid)
                    val c = ImageGenStore.character(g, charId) ?: throw ProviderError("캐릭터가 없습니다.")
                    val cb = combo(list)
                    val base = ImageGenStore.jobForCharacter(g, c)
                    val job = base.copy(basePrompt = ImageGenStore.joinSections(cb, g.o("style").s("quality")))
                    val png = ImageGenStore.generate(g, job, seed)
                    val name = "research_${System.currentTimeMillis()}.png"
                    withContext(Dispatchers.IO) { ImageGenStore.imageFile(cid, name).writeBytes(png) }
                    val o = load(cid)
                    val item = buildJsonObject { put("file", JsonPrimitive(name)); put("combo", JsonPrimitive(cb)); put("seed", JsonPrimitive(seed)); put("createdAt", JsonPrimitive(System.currentTimeMillis())) }
                    save(cid, o.with("items" to JsonArray(listOf(item) + o.arr("items"))))
                    progress[cid] = Run(i + 1, total)
                    // 매크로로 보이지 않게 0.1초 + 0~0.3초 랜덤 텀
                    if (i < total - 1) delay(100L + (0..300).random())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
            } catch (e: Throwable) {
                error[cid] = e.message ?: "생성 실패"
            } finally {
                progress.remove(cid); jobs.remove(cid)
            }
        }
    }

    fun stop(cid: String) { jobs[cid]?.cancel() }

    fun delete(cid: String, files: Set<String>) {
        val o = load(cid)
        files.forEach { ImageGenStore.imageFile(cid, it).delete() }
        save(cid, o.with("items" to JsonArray(o.arr("items").filter { it.s("file") !in files })))
    }

    /** 라이브러리에 넣을 그림체: 작가 조합 + 지금 부정·모델·샘플러·스텝 (퀄리티 태그·가이던스는 건드리지 않음). */
    fun styleData(cid: String, combo: String): JsonObject {
        val st = ImageGenStore.load(cid).o("style")
        return buildJsonObject {
            st.s("model").takeIf { it.isNotBlank() }?.let { put("model name", JsonPrimitive(it)) }
            put("Steps", JsonPrimitive((st.d("steps") ?: 28.0).toInt()))
            st.s("sampler").takeIf { it.isNotBlank() }?.let { put("Sampler", JsonPrimitive(it)) }
            put("Art style prompt", JsonPrimitive(combo)); put("Art style negative", JsonPrimitive(st.s("negative")))
        }
    }
}
