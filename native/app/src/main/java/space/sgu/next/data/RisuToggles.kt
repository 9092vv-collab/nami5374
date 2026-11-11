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
        if (dat.startsWith("? ")) return jsNum(calcString(dat.substring(2), gv))
        val a = dat.split("::")
        val arg = { i: Int -> a.getOrElse(i + 1) { "" } }
        return when (a[0].trim().lowercase()) {
            "getglobalvar" -> gv(arg(0))
            "equal" -> if (arg(0) == arg(1)) "1" else "0"
            "notequal", "not_equal" -> if (arg(0) != arg(1)) "1" else "0"
            "and" -> if (arg(0) == "1" && arg(1) == "1") "1" else "0"
            "or" -> if (arg(0) == "1" || arg(1) == "1") "1" else "0"
            "not" -> if (arg(0) == "1") "0" else "1"
            "calc" -> jsNum(calcString(arg(0), gv))
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

    // ---------- {{? 식}} 계산 (RisuAI src/ts/process/infunctions.ts 그대로) ----------

    private fun jsNum(d: Double): String = when {
        d.isNaN() -> "NaN"
        d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
        d == Math.floor(d) && Math.abs(d) < 1e21 -> d.toLong().toString()
        else -> d.toString()
    }

    /** JS parseFloat: 앞쪽 숫자 부분만, 없으면 NaN */
    private fun parseFloatJs(s: String): Double {
        val m = Regex("^[+-]?(Infinity|\\d+\\.?\\d*(?:[eE][+-]?\\d+)?|\\.\\d+(?:[eE][+-]?\\d+)?)").find(s.trimStart()) ?: return Double.NaN
        val t = m.value
        return if (t.endsWith("Infinity")) (if (t.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY) else t.toDoubleOrNull() ?: Double.NaN
    }

    private fun isNumTok(t: String): Boolean { val v = parseFloatJs(t); return (!v.isNaN() && v != 0.0) || t == "0" }

    private val OPS = mapOf('+' to 2, '-' to 2, '*' to 3, '/' to 3, '^' to 4, '%' to 3, '<' to 1, '>' to 1, '|' to 1, '&' to 1,
        '≤' to 1, '≥' to 1, '=' to 1, '≠' to 1, '!' to 5)

    private fun toRpn(expr0: String): List<String> {
        val expr = expr0.replace(Regex("\\s+"), "")
        val toks = mutableListOf<String>()
        var last = ""
        for (i in expr.indices) {
            val c = expr[i]
            if (c == '-' && (i == 0 || expr[i - 1] in OPS || expr[i - 1] == '(')) last += c
            else if (c in OPS) { toks.add(if (last != "") last else "0"); last = ""; toks.add(c.toString()) }
            else last += c
        }
        toks.add(if (last != "") last else "0")
        val out = mutableListOf<String>()
        val st = ArrayDeque<Char>()
        for (t in toks) {
            if (isNumTok(t)) out.add(t)
            else if (t.length == 1 && t[0] in OPS) {
                val c = t[0]; val pr = OPS[c]!!
                while (st.isNotEmpty() && ((c != '!' && pr <= OPS[st.last()]!!) || (c == '!' && pr < OPS[st.last()]!!))) out.add(st.removeLast().toString())
                st.addLast(c)
            }
        }
        while (st.isNotEmpty()) out.add(st.removeLast().toString())
        return out
    }

    private fun jsTruthy(d: Double) = !(d.isNaN() || d == 0.0)

    private fun calcRpn(rpn: List<String>): Double {
        val st = ArrayDeque<Double>()
        fun pop() = if (st.isEmpty()) Double.NaN else st.removeLast()
        for (t in rpn) {
            if (isNumTok(t)) { st.addLast(parseFloatJs(t)); continue }
            val b = pop(); val a = pop()
            fun bool(v: Boolean) = if (v) 1.0 else 0.0
            when (t) {
                "+" -> st.addLast(a + b); "-" -> st.addLast(a - b); "*" -> st.addLast(a * b); "/" -> st.addLast(a / b)
                "^" -> st.addLast(Math.pow(a, b)); "%" -> st.addLast(a % b)
                "<" -> st.addLast(bool(a < b)); ">" -> st.addLast(bool(a > b))
                "|" -> st.addLast(if (jsTruthy(a)) a else b); "&" -> st.addLast(if (jsTruthy(a)) b else a)
                "≤" -> st.addLast(bool(a <= b)); "≥" -> st.addLast(bool(a >= b))
                "=" -> st.addLast(bool(a == b)); "≠" -> st.addLast(bool(a != b))
                "!" -> st.addLast(bool(!jsTruthy(b)))
            }
        }
        return if (st.isEmpty()) 0.0 else st.removeLast()
    }

    private fun execCalc(text0: String, gv: (String) -> String): Double {
        fun num(v: String): String { val d = parseFloatJs(v); return if (d.isNaN()) "0" else jsNum(d) }
        val text = text0
            .replace(Regex("\\$([a-zA-Z0-9_]+)")) { "0" }
            .replace(Regex("@([a-zA-Z0-9_]+)")) { num(gv(it.groupValues[1])) }
            .replace("&&", "&").replace("||", "|").replace("<=", "≤").replace(">=", "≥").replace("==", "=").replace("!=", "≠")
            .replace(Regex("null", RegexOption.IGNORE_CASE), "0")
        return calcRpn(toRpn(text))
    }

    fun calcString(text: String, gv: (String) -> String): Double {
        val depth = mutableListOf(StringBuilder())
        for (c in text) {
            when {
                c == '(' -> depth.add(StringBuilder())
                c == ')' && depth.size > 1 -> { val r = execCalc(depth.removeAt(depth.size - 1).toString(), gv); depth.last().append(jsNum(r)) }
                else -> depth.last().append(c)
            }
        }
        return execCalc(depth.joinToString(""), gv)
    }

    // ---------- 토글 찾기: 이 토글을 조건으로 쓰는 {{#…}} … {{/…}} 구간 ----------

    data class Use(val header: String, val segment: String, val active: Boolean?, val start: Int = 0, val end: Int = 0)

    private fun refersTo(header: String, key: String): Boolean {
        val k = Regex.escape(key)
        val end = "(?![\\p{L}\\p{N}_])"
        return Regex("toggle_$k$end").containsMatchIn(header) ||
            Regex("(^|::)toggle::$k(::|$)").containsMatchIn(header) ||
            Regex("(^|::)$k::(tis|tisnot)(::|$)").containsMatchIn(header)
    }

    /** {{#if…}}·{{#when…}}처럼 이 토글로 감싼 구간들 (중첩도 각각). active = 지금 값으로 앞쪽(참) 내용이 들어가는지. */
    fun findUses(text: String, key: String, values: Map<String, String>): List<Use> {
        val out = mutableListOf<Pair<Int, Use>>()
        val stack = ArrayDeque<Pair<Int, String>>() // start index, header
        var i = 0
        while (i < text.length - 1) {
            if (text[i] == '{' && text[i + 1] == '{') {
                var depth = 1; var j = i + 2
                while (j < text.length - 1 && depth > 0) {
                    if (text[j] == '{' && text[j + 1] == '{') { depth++; j += 2 }
                    else if (text[j] == '}' && text[j + 1] == '}') { depth--; if (depth == 0) break; j += 2 }
                    else j++
                }
                if (depth != 0) break
                val dat = text.substring(i + 2, j)
                val endIdx = j + 2
                if (dat.startsWith("#")) stack.addLast(i to dat)
                else if (dat.startsWith("/") && !dat.startsWith("//") && stack.isNotEmpty()) {
                    val (st, h) = stack.removeLast()
                    if (refersTo(h, key)) out.add(st to Use(h, text.substring(st, endIdx), headerActive(h, values), st, endIdx))
                }
                i = endIdx
            } else i++
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    private fun headerActive(header: String, values: Map<String, String>): Boolean? {
        fun gv(name: String): String = if (name.startsWith("toggle_")) values[name.removePrefix("toggle_")] ?: "null" else "null"
        val resolved = render(header, values)
        val b = blockStart(resolved, ::gv) ?: return null
        return b.type == "parse" || b.type == "ifpure" || b.type == "newif"
    }
}
