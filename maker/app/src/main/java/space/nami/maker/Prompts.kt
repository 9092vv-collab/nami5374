package space.nami.maker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Prompt presets like Nami next: a preset is a list of blocks sent top to bottom.
 * - text: fixed text ({{tiles}}, {{graphics}} are filled in)
 * - map: the map the user is looking at ({{slot}} = text map), can attach the picture
 * - command: what the user typed ({{slot}})
 * role "system" blocks become the system prompt; role "user" blocks become the user's message, in order.
 */
object Prompts {
    const val SLOT = "{{slot}}"
    val TYPE_LABELS = mapOf("text" to "텍스트", "story" to "소설·계획", "cast" to "등장인물", "map" to "지금 맵", "command" to "사용자 메시지")
    /** next처럼 블록마다 역할: 시스템 / 어시스턴트 / 유저. 맨 끝 어시스턴트 블록 = 프리필 */
    val ROLES = listOf("system" to "시스템", "assistant" to "어시스턴트", "user" to "유저")
    fun roleLabel(r: String) = ROLES.firstOrNull { it.first == r }?.second ?: "시스템"
    val TYPE_DESC = mapOf(
        "text" to "직접 쓰는 고정 글. {{tiles}} = 타일 범례, {{graphics}} = 그래픽 목록으로 바뀌어요.",
        "story" to "이 게임의 소설과 AI가 세운 제작 계획이 {{slot}} 자리에 들어가요. 둘 다 비어 있으면 이 블록은 안 보내요.",
        "cast" to "사용자가 정한 등장인물(주연·조연: 이름, 설명, 걷기 그림, 표정 목록)이 {{slot}} 자리에 들어가요. 등장인물이 없으면 이 블록은 안 보내요.",
        "map" to "지금 보고 있는 맵의 JSON(바닥·위층 기호, 이벤트, 맵 목록)이 {{slot}} 자리에 들어가요. 그림 첨부를 켜면 맵 그림도 같이 가요(기본은 꺼짐).",
        "command" to "사용자가 채팅에 쓴 말이 {{slot}} 자리에 들어가요.",
    )

    var rev by mutableIntStateOf(0)
    private var file: File? = null
    private var root = JSONObject()

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "presets.json"); file = f
        root = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        if ((root.optJSONArray("presets")?.length() ?: 0) == 0) {
            root.put("presets", JSONArray().put(newPreset("기본", defaults()))).put("v", 2)
            save()
        } else if (root.optInt("v") < 2) {
            // 2 = JSON edits instead of tool calls: keep the old presets (renamed), add and use a new 기본
            presets().forEach { it.put("name", it.optString("name") + " (이전 방식)") }
            val np = newPreset("기본", defaults())
            root.put("presets", JSONArray(listOf(np) + presets())).put("active", np.getString("id")).put("v", 5)
            save()
        } else if (root.optInt("v") < 3) {
            // 3 = 소설로 게임 만들기: 이전 방식이 아닌 프리셋에 "소설로 게임 만들기"(system)와 "소설·계획"(user, 지금 맵 앞) 블록을 넣음
            presets().filter { !it.optString("name").endsWith("(이전 방식)") }.forEach { pr ->
                val l = blocks(pr).toMutableList()
                l.firstOrNull { it.optString("name") == "이벤트 규칙" }?.let { b ->
                    val t = b.optString("text")
                    if ("\"move\"" !in t) b.put("text", t.replace(TRANSFER_LINE, TRANSFER_LINE + "\n" + MOVE_LINE))
                }
                if (l.none { it.optString("type") == "story" }) {
                    val at = l.indexOfFirst { it.optString("type") == "map" }.let { if (it < 0) l.size else it }
                    l.add(at, storyBlock())
                    val sysAt = l.indexOfLast { it.optString("role") == "system" } + 1
                    l.add(sysAt, novelRules())
                    pr.put("blocks", JSONArray(l))
                }
            }
            root.put("v", 3); save()
        }
        if (root.optInt("v") < 4) {
            presets().forEach { pr ->
                val l = blocks(pr)
                l.firstOrNull { it.optString("name") == "이벤트 규칙" }?.let { b ->
                    val t = b.optString("text")
                    if ("\"goal\"" !in t && MOVE_LINE in t) b.put("text", t.replace(MOVE_LINE, MOVE_LINE + "\n" + GOAL_LINE))
                }
                l.firstOrNull { it.optString("name") == "소설로 게임 만들기" }?.let { b ->
                    val t = b.optString("text"); val k = "그 스위치를 조건으로)."
                    if ("goal" !in t && k in t) b.put("text", t.replace(k, k + "\n   - 장면이 끝나 다음에 가야 할 곳이 있으면 goal 명령으로 목적지 화살표를 켜 둔다."))
                }
                pr.put("blocks", JSONArray(l))
            }
            root.put("v", 4); save()
        }
        if (root.optInt("v") < 5) {
            presets().forEach { pr ->
                val l = blocks(pr)
                l.forEach { b ->
                    val t = b.optString("text"); val k = "- {\"op\":\"set_start\",\"map\":1,\"x\":0,\"y\":0}"
                    if (k in t && "set_hero" !in t) b.put("text", t.replace(k, k + "\n- {\"op\":\"set_hero\",\"graphic\":\"그래픽\"}  — 주인공 모습 바꾸기"))
                }
                pr.put("blocks", JSONArray(l))
            }
            root.put("v", 5); save()
        }
        if (root.optInt("v") < 6) {
            // 6 = AI 조수 이름 AIRI + {{user}}로 부르기: 기본 그대로인 '역할' 블록만 바꿈
            presets().forEach { pr ->
                val l = blocks(pr)
                l.forEach { b -> val t = b.optString("text"); if (OLD_ROLE in t) b.put("text", t.replace(OLD_ROLE, NEW_ROLE)) }
                pr.put("blocks", JSONArray(l))
            }
            root.put("v", 6); save()
        }
        if (root.optInt("v") < 7) {
            presets().forEach { pr -> pr.put("blocks", JSONArray(blocks(pr).map { upgrade(it) })) }
            root.put("v", 7); save()
        }
        if (root.optInt("v") < 8) {
            // 8 = 좌표 이동(move + x, y)
            presets().forEach { pr ->
                val l = blocks(pr)
                l.forEach { b -> val t = b.optString("text"); if (MOVE_LINE in t && "좌표 이동" !in t) b.put("text", t.replace(MOVE_LINE, MOVE_LINE + "\n" + MOVETO_LINE)) }
                pr.put("blocks", JSONArray(l))
            }
            root.put("v", 8); save()
        }
        if (root.optInt("v") < 9) {
            // 9 = 등장인물 블록(지금 맵 블록 바로 앞에)
            presets().forEach { pr ->
                val l = blocks(pr).toMutableList()
                if (l.none { it.optString("type") == "cast" }) {
                    val at = l.indexOfFirst { it.optString("type") == "map" }.let { if (it < 0) l.size else it }
                    l.add(at, castBlock())
                    pr.put("blocks", JSONArray(l))
                }
            }
            root.put("v", 9); save()
        }
    }

    private const val OLD_ROLE = """너는 게임 제작 앱 "Nami Maker"(쯔꾸르 같은 2D 탑다운 게임 제작 도구) 안의 AI 조수다.
사용자와 함께 대화·탐색 위주의 게임(어드벤처, 호러 쯔꾸르 같은)을 만든다. 전투는 아직 없다."""
    private const val NEW_ROLE = """너는 AIRI, 게임 제작 앱 "Nami Maker"(쯔꾸르 같은 2D 탑다운 게임 제작 도구) 안의 AI 조수다.
{{user}}와 함께 대화·탐색 위주의 게임(어드벤처, 호러 쯔꾸르 같은)을 만든다. 전투는 아직 없다.
reply에서 사용자는 항상 "{{user}}"라고 부른다."""

    /** 프롬프트의 {{user}}에 들어갈 이름 (비어 있으면 {{user}} 그대로 나감) */
    fun userName(): String = root.optString("userName")
    fun setUserName(v: String) { root.put("userName", v.trim()); save() }

    private fun save() { file?.writeText(root.toString()); rev++ }
    private fun id(p: String) = p + System.nanoTime().toString(36)

    fun block(type: String, role: String, name: String, text: String, image: Boolean = false): JSONObject =
        JSONObject().put("id", id("b")).put("type", type).put("enabled", true).put("role", role).put("name", name).put("text", text)
            .also { if (type == "map") it.put("image", image) }

    private fun newPreset(name: String, blocks: List<JSONObject>) = JSONObject().put("id", id("ps")).put("name", name).put("blocks", JSONArray(blocks))

    fun defaults(): List<JSONObject> = listOf(
        block("text", "system", "역할", """너는 AIRI, 게임 제작 앱 "Nami Maker"(쯔꾸르 같은 2D 탑다운 게임 제작 도구) 안의 AI 조수다.
{{user}}와 함께 대화·탐색 위주의 게임(어드벤처, 호러 쯔꾸르 같은)을 만든다. 전투는 아직 없다.
reply에서 사용자는 항상 "{{user}}"라고 부른다.
reply는 항상 한국어로, 상냥하고 짧게 쓴다. 이모지는 거의 쓰지 않는다."""),
        block("text", "system", "맵 보는 법", """## 맵 JSON
사용자 메시지에 지금 보고 있는 맵이 JSON으로 붙어 온다.
- ground: 바닥. 한 줄 = 한 행(y), 한 글자 = 한 칸(x). 대문자 기호.
- upper: 바닥 위에 올린 것(나무, 가구, 문 …). 소문자 기호, '.'은 없음.
- 좌표 (x, y): x는 왼쪽→오른쪽, y는 위→아래, 0부터. ground[y]의 x번째 글자가 그 칸이다.
- events: 이벤트 목록(id, kind, name, x, y, run, first=첫 명령 요약). 자세한 내용은 open으로 열어 본다.
- 그 아래 줄: start(주인공 시작 위치), maps(모든 맵), switches(쓰인 스위치)."""),
        block("text", "system", "기호", """## 기호
{{tiles}}
'지나감'이 없는 기호는 막힌다. upper가 있는 칸은 upper가 통행을 정한다(벽 W 위에 문 d를 두면 지나갈 수 있다)."""),
        block("text", "system", "이벤트 규칙", """## 이벤트
kind:
- npc: 눈에 보이는 것(사람, 상자, 표지판). 지나갈 수 없다. graphic: {{graphics}} 중 하나("none" 빼고).
- point: 이벤트 지점. 투명하고 지나갈 수 있다. 문 이동, 함정, 이야기 시작 지점에 쓴다.
run: action(바라보고 말 걸기·조사) | touch(밟으면) | auto(맵에 들어오면 한 번). npc 기본 action, point 기본 touch.
이벤트는 페이지 목록을 가질 수 있다. 게임은 조건(condition_switch가 켜짐)을 만족하는 페이지 중 가장 뒤의 것을 쓴다.
페이지: {"condition_switch": "", "graphic": "...", "dir": "down|left|right|up", "run": "...", "commands": [...]}
명령(commands):
- {"type":"text","speaker":"이름(없으면 빈칸)","text":"대사. 줄바꿈 \n, 한 번에 2~3줄"}
- {"type":"choice","options":["네","아니요"],"branches":[[...],[...]]}  (2~4개)
- {"type":"switch","name":"스위치","on":true}
- {"type":"if","cond":"switch","name":"스위치","on":true,"then":[...],"else":[...]}  /  {"type":"if","cond":"item","name":"아이템","then":[...],"else":[...]}
- {"type":"transfer","map":맵 id,"x":0,"y":0}  (장소 이동, 화면이 자동으로 어두워졌다 밝아짐)
- {"type":"move","target":"player|this|이벤트 id","route":"UULLR"}  (이동 경로: U D L R = 한 칸 걷기, u d l r = 그쪽 보기, . = 잠깐 쉬기. 다 걸을 때까지 기다림)
- {"type":"move","target":"player|this|이벤트 id","x":0,"y":0}  (좌표 이동: 그 칸까지 앱이 길을 찾아 자연스럽게 걸어감. 단순히 어디로 가게 할 때는 이쪽을 쓴다)
- {"type":"goal","map":맵 id,"target":"이벤트 id"}  (목적지 화살표: 그 이벤트 위에 화살표, 다른 맵이면 그 맵으로 가는 문을 가리킴, 그 이벤트를 실행하면 꺼짐. "target":"off" = 끄기)
- {"type":"item","name":"열쇠","amount":1}  (음수면 잃음)
- {"type":"wait","ms":500}  /  {"type":"fade","out":true}  /  {"type":"end"}
한 번만 일어날 일은 스위치를 켜고, 그 스위치를 조건으로 한 다음 페이지를 만든다(상자 열면 [상자1 열림] 켜고 페이지2는 빈 상자).
문으로 다른 맵에 가려면 문 칸에 point(run touch, transfer)를 두고, 도착 위치는 상대 맵의 문 바로 앞(문 위가 아닌 곳)으로 한다."""),
        block("text", "system", "답하는 법", """## 답 형식 — JSON 하나만
{"reply": "사용자에게 할 말", "edits": [ ... ]}
고칠 게 없으면 edits는 []. 질문에는 reply로만 답한다.
edits의 op (map을 안 쓰면 지금 맵):
- {"op":"paint","map":1,"layer":"ground|upper","x":0,"y":0,"rows":["WWW","WFW"]}  — (x,y)를 왼쪽 위로 여러 줄 칠함. '?'는 그대로 둠. upper에서 '.'은 지움
- {"op":"fill","map":1,"layer":"ground|upper","x":0,"y":0,"w":3,"h":2,"tile":"G"}
- {"op":"add_event","map":1,"kind":"npc|point","name":"","x":0,"y":0,"graphic":"villager","dir":"down","run":"action","commands":[...]}  (여러 페이지면 "pages":[...])
- {"op":"edit_event","map":1,"id":1, 바꿀 칸만}  — commands나 pages를 주면 통째로 바뀐다
- {"op":"delete_event","map":1,"id":1}
- {"op":"add_map","ref":"house","name":"집 안","w":12,"h":9,"fill":"F"}  — 뒤의 op에서 "map":"house"로 가리킬 수 있다(id는 아직 모르니까)
- {"op":"edit_map","map":1,"name":"","w":20,"h":15}
- {"op":"set_start","map":1,"x":0,"y":0}
- {"op":"set_hero","graphic":"그래픽"}  — 주인공 모습 바꾸기
이벤트의 자세한 대사·명령이 필요하면 edits 없이 {"open":[이벤트 id, ...]}만 답한다(다른 맵이면 {"map":2,"id":1}). 앱이 내용을 보내 주면 그때 원래대로 답한다.
op은 위에서부터 순서대로 적용된다. 적용 뒤 앱이 문제(막힌 도착 칸 등)를 찾으면 다음 메시지에 알려 준다."""),
        block("text", "system", "일하는 법", """## 일하는 법
- 맵 JSON을 읽고 정확한 좌표를 계산해 칠한다. 건물은 paint로 여러 줄을 한 번에.
- 길이 막혀 못 가는 곳이 생기지 않게, npc가 문이나 길을 막지 않게.
- 사용자가 맵에 대해 물으면 JSON을 읽고 구체적으로 답한다.
- reply에는 무엇을 했는지 1~3문장. 사용자는 되돌리기 한 번으로 네 작업 전체를 취소할 수 있다."""),
        novelRules(),
        storyBlock(),
        castBlock(),
        block("map", "user", "지금 맵", "[지금 맵]\n{{slot}}", image = false),
        block("command", "user", "사용자 메시지", "[사용자]\n{{slot}}"),
    )

    private const val TRANSFER_LINE = """- {"type":"transfer","map":맵 id,"x":0,"y":0}  (장소 이동, 화면이 자동으로 어두워졌다 밝아짐)"""
    private const val GOAL_LINE = """- {"type":"goal","map":맵 id,"target":"이벤트 id"}  (목적지 화살표: 그 이벤트 위에 화살표, 다른 맵이면 그 맵으로 가는 문을 가리킴, 그 이벤트를 실행하면 꺼짐. "target":"off" = 끄기)"""
    private const val MOVETO_LINE = """- {"type":"move","target":"player|this|이벤트 id","x":0,"y":0}  (좌표 이동: 그 칸까지 앱이 길을 찾아 자연스럽게 걸어감. 단순히 어디로 가게 할 때는 이쪽을 쓴다)"""
    private const val MOVE_LINE = """- {"type":"move","target":"player|this|이벤트 id","route":"UULLR"}  (이동 경로: U D L R = 한 칸 걷기, u d l r = 그쪽 보기, . = 잠깐 쉬기. 다 걸을 때까지 기다림)"""

    fun storyBlock() = block("story", "user", "소설·계획", "[소설과 제작 계획]\n{{slot}}")

    /** 등장인물 블록(9): 목록 + 쓰는 법. */
    fun castBlock() = block("cast", "user", "등장인물", CAST_TEXT)
    private const val CAST_TEXT = """[등장인물 — {{user}}가 정한 주연·조연]
{{slot}}
- 이 인물들은 이름 그대로 쓰고, 이벤트로 만들 때 graphic은 그 인물의 sprite를 쓴다.
- stand가 true인 인물이 말하는 text 명령에는 "face"로 표정을 넣을 수 있다: {"type":"text","speaker":"이름","face":"표정","text":"…"} — face는 그 인물의 faces 중 하나만. 기본 표정이면 face를 빼도 된다.
- 엑스트라(지나가는 마을 사람 등)는 목록에 없어도 알아서 만들어도 된다."""

    fun novelRules() = block("text", "system", "소설로 게임 만들기", """## 소설로 게임 만들기
사용자가 소설을 넣어 두면 [소설과 제작 계획]으로 함께 온다. 소설을 쯔꾸르 게임(맵, 대사, 이동, 진행)으로 옮긴다.
1. 계획 먼저: 제작 계획이 비어 있으면 먼저 소설을 읽고 계획을 세워 {"op":"set_plan","plan":"..."}로 저장한다. 맵은 아직 만들지 않는다.
   계획에 쓸 것: 장소 목록(맵 이름·크기·분위기·연결), 등장인물(그래픽 고르기: {{graphics}}), 장면 순서(장 → 장면 → 장소, 쓰는 스위치 이름), 그리고 맨 아래 "진행: 아직 만든 장 없음".
2. 장 단위로 만들기: 사용자가 만들라고 하면 계획의 다음 장 하나만 만든다(맵·이벤트·대사·이동 경로·스위치). 만들고 나면 set_plan으로 계획의 "진행" 줄을 고친다(예: "진행: 1장 완료, 다음 2장").
3. 옮기는 법:
   - 대사는 소설의 말투를 살려 짧게 나눈다(한 번에 2~3줄). 서술은 이름 없는 대사나 이벤트 지점의 자동 실행으로 보여 준다.
   - 장면 연출은 move 명령으로 인물을 걷게 하고, wait·fade로 호흡을 준다. 장면이 바뀌면 transfer.
   - 다음 장면으로 넘어가는 조건은 스위치로 만든다(장면이 끝나면 스위치를 켜고, 다음 장면 이벤트는 그 스위치를 조건으로).
   - 장면이 끝나 다음에 가야 할 곳이 있으면 goal 명령으로 목적지 화살표를 켜 둔다.
   - 소설에 없는 사건이나 인물을 지어내지 않는다. 게임에 필요한 연결(문, 길)만 더한다.
4. 계획을 고치라고 하면 set_plan으로 계획 전체를 다시 쓴다.""")

    fun newBlock(type: String) = block(type, if (type == "text") "system" else "user", TYPE_LABELS[type] ?: type, if (type == "text") "" else SLOT, image = false)

    /** 예전 '프리필' 블록 종류 → 텍스트 + 어시스턴트 */
    private fun upgrade(b: JSONObject) = b.also { if (it.optString("type") == "prefill") it.put("type", "text").put("role", "assistant") }

    // ---------- presets ----------
    fun presets(): List<JSONObject> { val a = root.getJSONArray("presets"); return List(a.length()) { a.getJSONObject(it) } }
    fun preset(id: String?) = presets().firstOrNull { it.optString("id") == id }
    fun active(): JSONObject = preset(root.optString("active")) ?: presets().first()
    fun setActive(id: String) { root.put("active", id); save() }
    fun blocks(p: JSONObject): List<JSONObject> { val a = p.optJSONArray("blocks") ?: JSONArray(); return List(a.length()) { a.getJSONObject(it) } }

    fun add(name: String, blocks: List<JSONObject> = defaults()): String {
        val p = newPreset(name, blocks.map { JSONObject(it.toString()).put("id", id("b")) })
        root.put("presets", JSONArray(presets() + p)); save(); return p.getString("id")
    }
    fun copy(id: String): String? { val p = preset(id) ?: return null; return add(p.optString("name") + " 사본", blocks(p)) }
    fun remove(id: String) {
        if (presets().size <= 1) return
        root.put("presets", JSONArray(presets().filter { it.optString("id") != id }))
        if (root.optString("active") == id) root.remove("active")
        save()
    }
    fun rename(id: String, name: String) { preset(id)?.put("name", name); save() }
    fun reset(id: String) { preset(id)?.put("blocks", JSONArray(defaults())); save() }

    fun setBlocks(pid: String, l: List<JSONObject>) { preset(pid)?.put("blocks", JSONArray(l)); save() }
    fun patchBlock(pid: String, bid: String, k: String, v: Any) {
        preset(pid)?.let { p -> blocks(p).firstOrNull { it.optString("id") == bid }?.put(k, v) }; save()
    }
    fun move(pid: String, bid: String, d: Int) {
        val p = preset(pid) ?: return
        val l = blocks(p).toMutableList(); val i = l.indexOfFirst { it.optString("id") == bid }; val j = i + d
        if (i < 0 || j !in l.indices) return
        val t = l[i]; l[i] = l[j]; l[j] = t; setBlocks(pid, l)
    }
    fun removeBlock(pid: String, bid: String) { val p = preset(pid) ?: return; setBlocks(pid, blocks(p).filter { it.optString("id") != bid }) }
    fun addBlock(pid: String, type: String): String { val p = preset(pid)!!; val b = newBlock(type); setBlocks(pid, blocks(p) + b); return b.getString("id") }

    // ---------- export / import (clipboard JSON) ----------
    fun export(p: JSONObject): String = JSONObject().put("nami_maker_preset", 1).put("name", p.optString("name")).put("blocks", p.optJSONArray("blocks")).toString(2)
    fun exportAll(): String = JSONObject().put("nami_maker_preset", 1)
        .put("presets", JSONArray(presets().map { JSONObject().put("name", it.optString("name")).put("blocks", it.optJSONArray("blocks")) })).toString(2)
    /** 파일 이름으로 쓸 수 있게 */
    fun fileName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "프리셋" } + ".json"

    /** Returns the new preset id, or null if the text isn't a preset. Accepts one preset or a list. */
    fun import(text: String): Int {
        val found = mutableListOf<JSONObject>()
        runCatching {
            val t = text.trim()
            if (t.startsWith("[")) JSONArray(t).let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { found.add(it) } }
            else JSONObject(t).let { o -> o.optJSONArray("presets")?.let { a -> for (i in 0 until a.length()) found.add(a.getJSONObject(i)) } ?: found.add(o) }
        }
        var n = 0
        found.forEach { o ->
            val a = o.optJSONArray("blocks") ?: return@forEach
            val bl = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { b -> upgrade(b) } }.filter { it.optString("type") in TYPE_LABELS.keys }
            if (bl.isEmpty()) return@forEach
            add(o.optString("name").ifBlank { "가져온 프리셋" }, bl); n++
        }
        return n
    }

    // ---------- building a request ----------
    private fun fill(t: String) = t.replace("{{tiles}}", Tiles.legend()).replace("{{graphics}}", Sprites.ids.joinToString(", "))
        .let { s -> userName().let { u -> if (u.isBlank()) s else s.replace("{{user}}", u) } }
    private fun slot(t: String, v: String) = if (SLOT in t) t.replace(SLOT, v) else if (t.isBlank()) v else "$t\n\n$v"

    /**
     * system = 시스템 블록을 이어 붙인 것. content = 나머지 블록(순서대로), roles = content 칸마다 역할(user/assistant).
     * next처럼 맨 끝의 어시스턴트 블록들은 prefill(AI 답 첫머리)이 되고 content에는 안 들어감.
     */
    class Built(val system: String, val content: JSONArray, val mapIdx: JSONArray, val prefill: String = "", val roles: JSONArray = JSONArray())

    fun build(p: Project, mapId: Int, userText: String): Built {
        val bl = blocks(active()).filter { it.optBoolean("enabled", true) }
        val sys = StringBuilder()
        val m = p.map(mapId) ?: p.maps[0]
        var hasCommand = false
        // (역할, 블록 종류, 글, 맵 그림 첨부?)
        val seq = ArrayList<Triple<String, String, Pair<String, Boolean>>>()
        for (b in bl) {
            val type = b.optString("type"); val t = b.optString("text")
            val text = when (type) {
                "map" -> slot(t, MapJson.describe(p, m.id))
                "story" -> if (p.story.isBlank() && p.plan.isBlank()) "" else slot(t,
                    "## 소설\n" + p.story.ifBlank { "(없음)" } + "\n\n## 제작 계획 (set_plan으로 고침)\n" + p.plan.ifBlank { "(아직 없음)" })
                "cast" -> Stands.describe(p).let { d -> if (d.isBlank()) "" else slot(fill(t), d) }
                "command" -> { hasCommand = true; slot(t, userText) }
                else -> fill(t)
            }
            if (text.isBlank()) continue
            when (b.optString("role")) {
                "system", "" -> { if (sys.isNotEmpty()) sys.append("\n\n"); sys.append(text) }
                else -> seq.add(Triple(if (b.optString("role") == "assistant") "assistant" else "user", type, text to (type == "map" && b.optBoolean("image", false))))
            }
        }
        if (!hasCommand) seq.add(Triple("user", "command", userText to false))
        // 맨 끝 어시스턴트 = 프리필
        val pre = StringBuilder()
        while (seq.isNotEmpty() && seq.last().first == "assistant") { pre.insert(0, seq.removeAt(seq.size - 1).third.first) }
        val content = JSONArray(); val mapIdx = JSONArray(); val roles = JSONArray()
        for ((role, type, tx) in seq) {
            if (type == "map" || type == "story" || type == "cast") mapIdx.put(content.length())
            content.put(JSONObject().put("type", "text").put("text", tx.first)); roles.put(role)
            if (tx.second) {
                mapIdx.put(content.length())
                content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/png").put("data", Shot.base64(p, m))))
                roles.put(role)
            }
        }
        return Built(sys.toString(), content, mapIdx, pre.toString(), roles)
    }
}
