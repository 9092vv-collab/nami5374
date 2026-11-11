package space.sgu.next.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * RisuAI 프롬프트 프리셋 토글 (customPromptTemplateToggle) + 토글을 적용한 프롬프트 미리보기.
 * 문법·조건 처리는 RisuAI 원본을 그대로 옮김: src/ts/util.ts parseToggleSyntax,
 * src/ts/parser/parser.svelte.ts blockStartMatcher / blockEndMatcher / risuChatParser, src/ts/cbs.ts.
 * 토글 값은 RisuAI처럼 프리셋 파일이 아니라 앱에 따로 저장 (프리셋 이름별).
 * 값: 체크 "1"/"0", 목록 = 고른 항목 번호("0","1"…), 글 = 입력한 글. 안 정한 값은 RisuAI처럼 "null".
 */
object RisuToggles {
    /** type: check, select, text, textarea, caption, divider, group, groupEnd */
    data class Toggle(val key: String, val label: String, val type: String, val options: List<String> = emptyList())

    fun parse(template: String): List<Toggle> {
        if (template.isEmpty()) return emptyList()
        val out = mutableListOf<Toggle>()
        for (line in template.split("\n")) {
            val parts = line.split("=")
            val key = parts.getOrElse(0) { "" }
            val value = parts.getOrNull(1)
            val type = parts.getOrNull(2)
            val option = parts.getOrNull(3)
            when {
                type == "group" || type == "groupEnd" || type == "divider" -> out.add(Toggle(key, value ?: "", type))
                type == "caption" && !value.isNullOrEmpty() -> out.add(Toggle(key, value, "caption"))
                key.isNotEmpty() && !value.isNullOrEmpty() -> out.add(Toggle(key, value,
                    if (type == "select" || type == "text" || type == "textarea") type else "check",
                    option?.split(",") ?: emptyList()))
            }
        }
        return out
    }

    // ---------- 값 저장 (프리셋 이름별) ----------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("risu_toggles", Context.MODE_PRIVATE)

    fun load(ctx: Context, preset: String): Map<String, String> = runCatching {
        val raw = prefs(ctx).getString(preset, null) ?: return emptyMap()
        Json.parseToJsonElement(raw).jsonObject.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    fun save(ctx: Context, preset: String, values: Map<String, String>) {
        prefs(ctx).edit().putString(preset, JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()).apply()
    }

    // ---------- 미리보기용 CBS 처리 (토글 관련만, 나머지 {{…}}는 그대로 둠) ----------

    private class Block(val type: String, val type2: String? = null) // parse, ifpure, newif, newif-falsy, ignore

    private fun truthy(s: String?) = s == "true" || s == "1"

    fun render(src: String, values: Map<String, String>): String {
        fun gv(name: String): String = if (name.startsWith("toggle_")) values[name.removePrefix("toggle_")] ?: "null" else "null"

        val nested = ArrayDeque<StringBuilder>().apply { addFirst(StringBuilder()) }
        val stackType = HashMap<Int, Int>()
        val blocks = HashMap<Int, Block>()
        val pure = HashSet<Int>()
        var p = 0
        while (p < src.length) {
            val c = src[p]
            when {
                c == '{' && p + 1 < src.length && src[p + 1] == '{' -> {
                    p++; nested.addFirst(StringBuilder()); stackType[nested.size] = 1
                }
                c == '}' && p + 1 < src.length && src[p + 1] == '}' && nested.size > 1 && stackType[nested.size] == 1 -> {
                    p++
                    val dat = nested.removeFirst().toString()
                    val top = nested.first()
                    when {
                        dat.startsWith("#") || dat.startsWith(":") -> {
                            if (pure.isNotEmpty()) {
                                top.append("{{").append(dat).append("}}")
                                if (dat != ":else") { nested.addFirst(StringBuilder()); stackType[nested.size] = 6 }
                            } else {
                                val m = blockStart(dat, ::gv)
                                if (m == null) top.append("{{").append(dat).append("}}")
                                else {
                                    nested.addFirst(StringBuilder()); stackType[nested.size] = 5; blocks[nested.size] = m
                                    if (m.type == "ignore") pure.add(nested.size)
                                }
                            }
                        }
                        dat.startsWith("/") && !dat.startsWith("//") && stackType[nested.size] == 5 -> {
                            val bt = blocks.remove(nested.size)!!
                            pure.remove(nested.size)
                            val body = nested.removeFirst().toString()
                            nested.first().append(blockEnd(body, bt))
                        }
                        dat.startsWith("/") && !dat.startsWith("//") && stackType[nested.size] == 6 -> {
                            val sft = nested.removeFirst()
                            nested.first().append(sft).append("{{").append(dat).append("}}")
                        }
                        pure.isNotEmpty() -> top.append("{{").append(dat).append("}}")
                        else -> top.append(call(dat, ::gv) ?: "{{$dat}}")
                    }
                }
                else -> nested.first().append(c)
            }
            p++
        }
        // 닫히지 않은 것은 글자 그대로 붙임
        while (nested.size > 1) {
            val t = stackType[nested.size]
            val s = nested.removeFirst()
            nested.first().append(if (t == 1) "{{" else "").append(s)
        }
        return nested.first().toString()
    }

    private fun call(dat: String, gv: (String) -> String): String? {
        val a = dat.split("::")
        val arg = { i: Int -> a.getOrElse(i + 1) { "" } }
        return when (a[0].trim().lowercase()) {
            "getglobalvar" -> gv(arg(0))
            "equal" -> if (arg(0) == arg(1)) "1" else "0"
            "notequal", "not_equal" -> if (arg(0) != arg(1)) "1" else "0"
            "and" -> if (arg(0) == "1" && arg(1) == "1") "1" else "0"
            "or" -> if (arg(0) == "1" || arg(1) == "1") "1" else "0"
            "not" -> if (arg(0) == "1") "0" else "1"
            else -> null
        }
    }

    private fun jsSplit2(s: String): String? = s.split(" ").getOrNull(1)

    private fun blockStart(p1: String, gv: (String) -> String): Block? {
        if (p1.startsWith("#if")) {
            val state = jsSplit2(p1)
            return if (truthy(state)) Block(if (p1.startsWith("#if_pure")) "ifpure" else "parse") else Block("ignore")
        }
        if (p1.startsWith("#when")) {
            if (p1.startsWith("#when ")) return Block(if (truthy(jsSplit2(p1))) "newif" else "newif-falsy")
            if (!p1.startsWith("#when::")) return Block("newif-falsy")
            val st = p1.split("::").drop(1).toMutableList()
            if (st.size == 1) return Block(if (truthy(st[0])) "newif" else "newif-falsy")
            var mode = "normal"
            fun pop() = if (st.isEmpty()) "" else st.removeAt(st.size - 1)
            fun b(v: Boolean) = if (v) "1" else "0"
            while (st.size > 1) {
                val cond = pop()
                when (pop()) {
                    "not" -> st.add(b(!truthy(cond)))
                    "keep" -> { mode = "keep"; st.add(cond) }
                    "legacy" -> { mode = "legacy"; st.add(cond) }
                    "and" -> { val c2 = pop(); st.add(b(truthy(cond) && truthy(c2))) }
                    "or" -> { val c2 = pop(); st.add(b(truthy(cond) || truthy(c2))) }
                    "is" -> { val c2 = pop(); st.add(b(cond == c2)) }
                    "isnot" -> { val c2 = pop(); st.add(b(cond != c2)) }
                    "var" -> st.add("0") // 채팅 변수는 미리보기에 없음
                    "toggle" -> st.add(b(truthy(gv("toggle_$cond"))))
                    "vis" -> { pop(); st.add(b("null" == cond)) }
                    "visnot" -> { pop(); st.add(b("null" != cond)) }
                    "tis" -> st.add(b(gv("toggle_" + pop()) == cond))
                    "tisnot" -> st.add(b(gv("toggle_" + pop()) != cond))
                    ">" -> { val c2 = pop(); st.add(b((c2.toDoubleOrNull() ?: Double.NaN) > (cond.toDoubleOrNull() ?: Double.NaN))) }
                    "<" -> { val c2 = pop(); st.add(b((c2.toDoubleOrNull() ?: Double.NaN) < (cond.toDoubleOrNull() ?: Double.NaN))) }
                    ">=" -> { val c2 = pop(); st.add(b((c2.toDoubleOrNull() ?: Double.NaN) >= (cond.toDoubleOrNull() ?: Double.NaN))) }
                    "<=" -> { val c2 = pop(); st.add(b((c2.toDoubleOrNull() ?: Double.NaN) <= (cond.toDoubleOrNull() ?: Double.NaN))) }
                    else -> st.add(b(truthy(cond)))
                }
            }
            val ok = truthy(st.firstOrNull())
            return when (mode) {
                "keep" -> Block(if (ok) "newif" else "newif-falsy", "keep")
                "legacy" -> Block(if (ok) "parse" else "ignore")
                else -> Block(if (ok) "newif" else "newif-falsy")
            }
        }
        return null
    }

    private fun trimLines(s: String) = s.split("\n").joinToString("\n") { it.trimStart() }.trim()

    private fun blockEnd(p1: String, t: Block): String = when (t.type) {
        "parse" -> trimLines(p1.trim())
        "ifpure" -> p1
        "newif", "newif-falsy" -> {
            val lines = p1.split("\n").toMutableList()
            if (lines.size == 1) {
                val e = p1.indexOf("{{:else}}")
                if (e != -1) { if (t.type == "newif") p1.substring(0, e) else p1.substring(e + 9) }
                else if (t.type == "newif") p1 else ""
            } else {
                val elseLine = lines.indexOfFirst { it.trim() == "{{:else}}" }
                if (elseLine == -1 && t.type == "newif-falsy") ""
                else {
                    var l: List<String> = lines
                    if (elseLine != -1) l = if (t.type == "newif") lines.subList(0, elseLine) else lines.subList(elseLine + 1, lines.size)
                    if (t.type2 != "keep") {
                        l = l.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
                    }
                    l.joinToString("\n")
                }
            }
        }
        else -> "" // ignore
    }
}
