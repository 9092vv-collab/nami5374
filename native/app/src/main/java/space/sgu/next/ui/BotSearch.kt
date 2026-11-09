package space.sgu.next.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.sgu.next.data.Fields
import space.sgu.next.data.SguData
import space.sgu.next.data.WFields

/** 봇 전체 검색 (original module 1671 + the search bar of 1655 / match cards of 1669). */
object BotSearch {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    fun close() { open = false; query = "" }

    val Match = Color(0xFF2F8F5B)
    private val pretty = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = " " }

    /** Text of a field as the AI sees it; the lorebook as JSON. Toggles and image-template fields are skipped. */
    fun fieldText(d: JsonObject, id: String): String {
        val f = WFields.map[id] ?: return ""
        if (f.kind == "toggle" || f.kind == "gen") return ""
        return runCatching {
            if (f.kind == "lorebook") pretty.encodeToString(JsonElement.serializer(), d["lorebook"] ?: JsonArray(emptyList()))
            else SguData.serialize(d, id)
        }.getOrDefault("")
    }

    fun countMatches(text: String, q: String): Int {
        if (q.isEmpty()) return 0
        val t = text.lowercase(); val s = q.lowercase()
        var n = 0; var i = t.indexOf(s)
        while (i != -1) { n++; i = t.indexOf(s, i + s.length) }
        return n
    }

    data class Snip(val before: String, val hit: String, val after: String)

    fun snippets(text: String, q: String, max: Int = 12, ctx: Int = 28): List<Snip> {
        val out = mutableListOf<Snip>()
        if (q.isEmpty()) return out
        val t = text.lowercase(); val s = q.lowercase()
        var i = t.indexOf(s)
        while (i != -1 && out.size < max) {
            val a = maxOf(0, i - ctx); val b = minOf(text.length, i + s.length + ctx)
            out.add(Snip((if (a > 0) "…" else "") + text.substring(a, i).replace(Regex("\\s+"), " "), text.substring(i, i + s.length),
                text.substring(i + s.length, b).replace(Regex("\\s+"), " ") + if (b < text.length) "…" else ""))
            i = t.indexOf(s, i + s.length)
        }
        return out
    }

    fun searchAll(d: JsonObject, q: String): Map<String, Int> {
        if (q.isBlank()) return emptyMap()
        return WFields.all.mapNotNull { f -> countMatches(fieldText(d, f.id), q).takeIf { it > 0 }?.let { f.id to it } }.toMap()
    }

    fun groupOf(id: String) = WFields.map[id]?.group ?: ""
}

@Composable
fun SearchBarRow(total: Int, fields: Int) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, tint = Sgu.Sub, modifier = Modifier.padding(end = 8.dp))
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (BotSearch.query.isEmpty()) Text("봇 전체에서 검색", color = Sgu.Sub, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(BotSearch.query, { BotSearch.query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Sgu.Ink), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        if (BotSearch.query.isNotBlank()) Text("${total}개 일치 · ${fields}개 항목", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (total > 0) BotSearch.Match else Sgu.Sub)
        IconButton(onClick = { BotSearch.close() }) { Icon(Icons.Outlined.Close, "검색 닫기", tint = Sgu.Sub) }
    }
    HorizontalDivider(color = Sgu.Line)
}

/** Matching fields of one tab, with highlighted snippets; tapping a text field opens it. */
fun LazyListScope.searchResults(group: String, sgu: JsonObject, openField: (String) -> Unit) {
    val q = BotSearch.query.trim()
    if (q.isEmpty()) return
    val hits = WFields.all.filter { it.group == group }.mapNotNull { f ->
        val text = BotSearch.fieldText(sgu, f.id)
        BotSearch.countMatches(text, q).takeIf { it > 0 }?.let { Triple(f, text, it) }
    }
    if (hits.isEmpty()) { item(key = "search-none") { Text("이 탭에는 일치하는 항목이 없어요", style = MaterialTheme.typography.bodySmall, color = Sgu.Sub, modifier = Modifier.padding(6.dp)) }; return }
    hits.forEach { (f, text, n) ->
        item(key = "search-${f.id}") {
            val canOpen = Fields.byId[f.id]?.let { it.kind == space.sgu.next.data.FieldKind.Text || it.kind == space.sgu.next.data.FieldKind.LongText } == true
            LineCard(Modifier.border(2.dp, BotSearch.Match, RoundedCornerShape(24.dp)), onClick = if (canOpen) ({ openField(f.id) }) else null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        Text("${n}개 일치", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color.White,
                            modifier = Modifier.background(BotSearch.Match, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    BotSearch.snippets(text, q).forEach { s ->
                        Text(buildAnnotatedString {
                            append(s.before)
                            withStyle(SpanStyle(background = BotSearch.Match, color = Color.White, fontWeight = FontWeight.Bold)) { append(s.hit) }
                            append(s.after)
                        }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (n > 12) Text("외 ${n - 12}개", style = MaterialTheme.typography.labelSmall, color = Sgu.Sub)
                }
            }
        }
    }
}
