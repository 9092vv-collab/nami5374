package space.nami.browser

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/** A Samsung-Internet-style content blocker app found on the phone. */
class BlockerApp(val pkg: String, val label: String)

/**
 * Ad blocking with Adblock Plus–format filters.
 * Filters come from "content blocker" apps made for Samsung Internet (the same open API Yandex Browser reads):
 * the app exposes `content://<pkg>.contentBlocker.contentProvider` and declares the meta-data
 * `com.samsung.android.sbrowser.contentBlocker.interfaceVersion`.
 *
 * Supported: network rules (`||host^`, plain/wildcard patterns, `|` anchors, `^` separator, `@@` exceptions,
 * `$third-party`, `$domain=`, `$document`/`$elemhide`/`$generichide` exceptions) and element hiding (`##`, `#@#`).
 * Skipped: scriptlets, extended CSS, redirects, csp/removeparam and other rewrite options.
 */
class Blocker(private val ctx: Context, private val prefs: Prefs) {
    var ruleCount by mutableStateOf(0)
        private set
    var status by mutableStateOf("필터 없음")
        private set

    @Volatile private var engine: Engine = Engine.EMPTY
    private val dir = File(ctx.filesDir, "filters").apply { mkdirs() }

    fun findApps(): List<BlockerApp> {
        val pm = ctx.packageManager
        return runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.metaData?.containsKey(META) == true }
                .map { BlockerApp(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label }
        }.getOrDefault(emptyList())
    }

    /** Pulls filters from the enabled apps (and keeps a copy), then rebuilds the engine. */
    suspend fun refresh(fetch: Boolean = true) = withContext(Dispatchers.IO) {
        val enabled = prefs.blockerApps
        val texts = ArrayList<String>()
        var failed = 0
        for (pkg in enabled) {
            val cache = File(dir, "$pkg.txt")
            if (fetch) {
                val got = runCatching {
                    ctx.contentResolver.openInputStream(Uri.parse("content://$pkg.contentBlocker.contentProvider"))
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (!got.isNullOrBlank()) cache.writeText(got) else failed++
            }
            if (cache.exists()) texts.add(cache.readText())
        }
        for (url in prefs.filterUrls) {
            val cache = File(dir, "url_" + Integer.toHexString(url.hashCode()) + ".txt")
            val stale = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > 24L * 3600 * 1000
            if (fetch && stale) {
                val got = runCatching {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 60_000
                    try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
                }.getOrNull()
                if (!got.isNullOrBlank()) cache.writeText(got) else failed++
            }
            if (cache.exists()) texts.add(cache.readText())
        }
        val e = Engine.parse(texts)
        engine = e
        withContext(Dispatchers.Main) {
            ruleCount = e.size
            status = when {
                enabled.isEmpty() && prefs.filterUrls.isEmpty() -> "켜 둔 차단 앱이나 필터가 없어요"
                texts.isEmpty() -> "필터를 받지 못했어요"
                failed > 0 -> "규칙 ${e.size}개 (일부는 못 받음)"
                else -> "규칙 ${e.size}개 사용 중"
            }
        }
    }

    fun active(pageHost: String) = prefs.adblock && engine.size > 0 && !prefs.adblockOffFor(pageHost) && !engine.siteAllowed(pageHost)

    /** Called from shouldInterceptRequest (background thread). */
    fun intercept(url: String, pageHost: String): WebResourceResponse? {
        if (!active(pageHost)) return null
        if (!engine.blocks(url, pageHost)) return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    fun css(pageHost: String): String = if (active(pageHost)) engine.css(pageHost) else ""

    companion object { const val META = "com.samsung.android.sbrowser.contentBlocker.interfaceVersion" }

    // ---------------- engine ----------------

    class Rule(val regex: Regex?, val hostAnchor: String?, val thirdParty: Boolean?,
                       val domains: List<String>, val notDomains: List<String>)

    class Engine private constructor(
        private val blockByHost: HashMap<String, MutableList<Rule>>,
        private val blockByToken: HashMap<String, MutableList<Rule>>,
        private val blockOther: List<Rule>,
        private val allowByHost: HashMap<String, MutableList<Rule>>,
        private val allowOther: List<Rule>,
        private val docAllow: Set<String>,
        private val elemhideOff: Set<String>,
        private val genericHideOff: Set<String>,
        private val genericSel: List<String>,
        private val specificHide: HashMap<String, MutableList<String>>,
        private val hideExceptions: HashMap<String, MutableSet<String>>,
        private val genericExceptions: Set<String>,
        val size: Int,
    ) {
        fun siteAllowed(host: String) = suffixes(host).any { it in docAllow }

        fun blocks(url: String, pageHost: String): Boolean {
            val u = url.lowercase()
            val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
            val third = pageHost.isNotEmpty() && registrable(host) != registrable(pageHost)
            fun matches(r: Rule): Boolean {
                if (r.thirdParty != null && r.thirdParty != third) return false
                if (r.domains.isNotEmpty() && suffixes(pageHost).none { it in r.domains }) return false
                if (r.notDomains.isNotEmpty() && suffixes(pageHost).any { it in r.notDomains }) return false
                if (r.hostAnchor != null && suffixes(host).none { it == r.hostAnchor }) return false
                return r.regex?.containsMatchIn(u) ?: true
            }
            var hit = false
            for (s in suffixes(host)) { if (blockByHost[s]?.any(::matches) == true) { hit = true; break } }
            if (!hit) for (t in tokens(u)) { if (blockByToken[t]?.any(::matches) == true) { hit = true; break } }
            if (!hit) hit = blockOther.any(::matches)
            if (!hit) return false
            for (s in suffixes(host)) if (allowByHost[s]?.any(::matches) == true) return false
            return allowOther.none(::matches)
        }

        fun css(host: String): String {
            val sfx = suffixes(host).toList()
            if (sfx.any { it in elemhideOff }) return ""
            val except = HashSet<String>().apply { sfx.forEach { s -> hideExceptions[s]?.let { addAll(it) } } }
            val sb = StringBuilder()
            val specific = sfx.flatMap { specificHide[it] ?: emptyList() }.filter { it !in except }
            if (specific.isNotEmpty()) sb.append(buildGeneric(specific))
            if (sfx.none { it in genericHideOff }) {
                if (except.isEmpty()) sb.append(genericCss)
                else sb.append(buildGeneric(genericSel.filter { it !in except }))
            }
            return sb.toString()
        }

        private val genericCss: String by lazy { buildGeneric(genericSel) }

        companion object {
            val EMPTY = Engine(HashMap(), HashMap(), emptyList(), HashMap(), emptyList(), emptySet(), emptySet(), emptySet(),
                emptyList(), HashMap(), HashMap(), emptySet(), 0)

            /** One rule per selector, so a selector this WebView doesn't understand only drops itself. */
            private fun buildGeneric(sel: List<String>): String {
                val sb = StringBuilder(sel.size * 40)
                for (x in sel) sb.append(x).append("{display:none!important}\n")
                return sb.toString()
            }

            fun suffixes(host: String): Sequence<String> = sequence {
                var h = host
                while (true) {
                    yield(h)
                    val i = h.indexOf('.')
                    if (i < 0 || i == h.length - 1) break
                    h = h.substring(i + 1)
                }
            }

            fun registrable(host: String): String {
                val p = host.split('.')
                if (p.size <= 2) return host
                val two = setOf("co", "com", "net", "org", "or", "ne", "go", "ac", "gov", "edu")
                return if (p[p.size - 2] in two && p.last().length == 2) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
            }

            private val TOKEN = Regex("[a-z0-9%]{3,}")
            fun tokens(u: String) = TOKEN.findAll(u).map { it.value }

            private val SKIP_OPTS = setOf("popup", "csp", "redirect", "redirect-rule", "removeparam", "rewrite", "cookie",
                "replace", "header", "permissions", "urltransform", "jsonprune", "network", "badfilter", "empty", "mp4", "inline-script", "inline-font")

            fun parse(texts: List<String>): Engine {
                val blockByHost = HashMap<String, MutableList<Rule>>()
                val blockByToken = HashMap<String, MutableList<Rule>>()
                val blockOther = ArrayList<Rule>()
                val allowByHost = HashMap<String, MutableList<Rule>>()
                val allowOther = ArrayList<Rule>()
                val docAllow = HashSet<String>(); val elemOff = HashSet<String>(); val genOff = HashSet<String>()
                val generic = LinkedHashSet<String>()
                val genericExc = HashSet<String>()
                val specific = HashMap<String, MutableList<String>>()
                val hideExc = HashMap<String, MutableSet<String>>()
                var n = 0
                for (text in texts) for (raw in text.lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
                    // element hiding
                    val hideAt = line.indexOf("##").takeIf { it >= 0 }
                    val excAt = line.indexOf("#@#").takeIf { it >= 0 }
                    if (excAt != null) {
                        val sel = line.substring(excAt + 3).trim()
                        val doms = line.substring(0, excAt).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("~") }
                        if (doms.isEmpty()) genericExc.add(sel) else doms.forEach { hideExc.getOrPut(it) { HashSet() }.add(sel) }
                        n++; continue
                    }
                    if (hideAt != null) {
                        val sel = line.substring(hideAt + 2).trim()
                        if (sel.isEmpty() || sel.startsWith("+js") || sel.startsWith("^") || sel.contains('{') || sel.contains('}') || sel.contains(":-abp-") || sel.contains(":has-text") ||
                            sel.contains(":contains") || sel.contains(":upward") || sel.contains(":remove") || sel.contains(":style") ||
                            sel.contains(":matches-") || sel.contains(":xpath") || sel.contains(":min-text")) continue
                        val doms = line.substring(0, hideAt).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        val pos = doms.filter { !it.startsWith("~") }
                        if (pos.isEmpty()) generic.add(sel) else pos.forEach { specific.getOrPut(it) { ArrayList() }.add(sel) }
                        n++; continue
                    }
                    if (line.contains("#?#") || line.contains("#$#") || line.contains("#%#")) continue
                    // network rule
                    var r = line
                    val allow = r.startsWith("@@"); if (allow) r = r.substring(2)
                    var opts = ""
                    val d = r.lastIndexOf('$')
                    if (d >= 0 && OPTS_SHAPE.matches(r.substring(d + 1))) { opts = r.substring(d + 1); r = r.substring(0, d) }
                    var third: Boolean? = null
                    val doms = ArrayList<String>(); val notDoms = ArrayList<String>()
                    var skip = false; var docOpt = false; var elemOpt = false; var genOpt = false
                    if (opts.isNotEmpty()) for (o in opts.lowercase().split(',')) {
                        val name = o.substringBefore('=')
                        when {
                            name == "third-party" || name == "3p" -> third = true
                            name == "~third-party" || name == "1p" || name == "first-party" -> third = false
                            name == "domain" || name == "from" -> o.substringAfter('=').split('|').forEach {
                                if (it.startsWith("~")) notDoms.add(it.substring(1)) else if (it.isNotEmpty()) doms.add(it)
                            }
                            name == "document" || name == "doc" -> docOpt = true
                            name == "elemhide" || name == "ehide" -> elemOpt = true
                            name == "generichide" || name == "ghide" -> genOpt = true
                            name in SKIP_OPTS || name.removePrefix("~") in SKIP_OPTS -> skip = true
                        }
                    }
                    if (skip) continue
                    val pat = r.lowercase()
                    val anchor = Regex("^\\|\\|([a-z0-9.\\-]+)(.*)$").find(pat)
                    val host = anchor?.groupValues?.get(1)?.trimEnd('.')
                    val rest = anchor?.groupValues?.get(2) ?: ""
                    val hostOk = host != null && host.isNotEmpty() && (rest.isEmpty() || rest[0] == '^' || rest[0] == '/' || rest[0] == ':')
                    if (allow && (docOpt || elemOpt || genOpt)) {
                        if (hostOk) {
                            if (docOpt) docAllow.add(host!!)
                            if (elemOpt) elemOff.add(host!!)
                            if (genOpt) genOff.add(host!!)
                        }
                        n++; continue
                    }
                    if (docOpt) continue
                    if (pat.isEmpty() && doms.isEmpty()) continue
                    val trivial = rest.isEmpty() || rest == "^" || rest == "^|" || rest == "*"
                    val rule = if (hostOk) Rule(if (trivial) null else (toRegex(pat) ?: continue), host, third, doms, notDoms)
                        else Rule(if (pat.isEmpty() || pat == "*") null else (toRegex(pat) ?: continue), null, third, doms, notDoms)
                    n++
                    if (allow) {
                        if (hostOk) allowByHost.getOrPut(host!!) { ArrayList() }.add(rule) else allowOther.add(rule)
                    } else if (hostOk) blockByHost.getOrPut(host!!) { ArrayList() }.add(rule)
                    else {
                        val tok = pickToken(pat)
                        if (tok != null) blockByToken.getOrPut(tok) { ArrayList() }.add(rule) else blockOther.add(rule)
                    }
                }
                return Engine(blockByHost, blockByToken, blockOther, allowByHost, allowOther, docAllow, elemOff, genOff,
                    generic.filter { it !in genericExc }, specific, hideExc, genericExc, n)
            }

            private val OPTS_SHAPE = Regex("^~?[a-z0-9\\-]+(=[^,]*)?(,~?[a-z0-9\\-]+(=[^,]*)?)*$", RegexOption.IGNORE_CASE)

            /** A token that must appear whole in any matching URL (both ends bounded), for indexing. */
            private fun pickToken(p: String): String? {
                if (p.startsWith("/") && p.endsWith("/")) return null
                val startAnchored = p.startsWith("|")
                val endAnchored = p.endsWith("|")
                val body = p.trimStart('|').trimEnd('|')
                return TOKEN.findAll(body).filter { m ->
                    val before = if (m.range.first == 0) (if (startAnchored) '/' else '*') else body[m.range.first - 1]
                    val after = if (m.range.last == body.length - 1) (if (endAnchored) '/' else '*') else body[m.range.last + 1]
                    before != '*' && after != '*' && m.value !in setOf("http", "https", "www", "com", "net", "org")
                }.map { it.value }.maxByOrNull { it.length }
            }

            /** ABP pattern → regex. `||` = scheme + any subdomain, `^` = separator, `*` = anything, `|` = start/end. */
            private fun toRegex(p: String): Regex? = runCatching {
                if (p.length > 2 && p.startsWith("/") && p.endsWith("/")) return@runCatching Regex(p.substring(1, p.length - 1))
                var s = p
                val sb = StringBuilder()
                when {
                    s.startsWith("||") -> { sb.append("^[a-z][a-z0-9+.\\-]*://(?:[^/?#]*\\.)?"); s = s.substring(2) }
                    s.startsWith("|") -> { sb.append("^"); s = s.substring(1) }
                }
                val end = s.endsWith("|"); if (end) s = s.dropLast(1)
                for (c in s) when (c) {
                    '*' -> sb.append(".*")
                    '^' -> sb.append("(?:[^\\w.%\\-]|$)")
                    in "\\.+?()[]{}$|" -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                if (end) sb.append("$")
                Regex(sb.toString())
            }.getOrNull()
        }
    }
}
