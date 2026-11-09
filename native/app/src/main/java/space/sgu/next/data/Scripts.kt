package space.sgu.next.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 스크립트/기타/로어북 editors, writing the exact Character Card V3 shapes the original writes
 * (module 1653 buildCardV3) and reading them back like its import (module 1042).
 * Everything works on card.data and keeps fields it doesn't know about.
 */
object Scripts {
    private val RISU = listOf("extensions", "risuai")
    val REGEX_PATH = RISU + "customScripts"
    val TRIGGER_PATH = RISU + "triggerscript"
    val BIAS_PATH = RISU + "bias"
    val ONBOARDING_PATH = RISU + "onboarding"
    val LORE_PATH = listOf("character_book", "entries")
    private val LOW_LEVEL_PATH = RISU + "lowLevelAccess"
    private val pretty = Json { prettyPrint = true }

    fun str(o: JsonObject?, k: String) = ((o?.get(k)) as? JsonPrimitive)?.contentOrNull ?: ""
    fun bool(o: JsonObject?, k: String): Boolean {
        val p = o?.get(k) as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: (p.contentOrNull?.lowercase() == "true")
    }
    fun num(o: JsonObject?, k: String): Double? = ((o?.get(k)) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    fun objects(data: JsonObject?, path: List<String>) = ((Fields.get(data, path) as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
    private fun JsonObject.with(vararg p: Pair<String, JsonElement?>): JsonObject {
        val m = toMutableMap()
        p.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        return JsonObject(m)
    }
    private fun s(v: String) = JsonPrimitive(v)
    private fun b(v: Boolean) = JsonPrimitive(v)

    // ---------- 정규식 스크립트 ----------

    val REGEX_TYPES = listOf(
        // RisuAI RegexData.svelte 순서·이름 그대로 ("disabled" = 비활성화됨)
        "editinput" to "입력문 수정", "editoutput" to "출력문 수정", "editprocess" to "리퀘스트 데이터 수정",
        "editdisplay" to "디스플레이 수정", "edittrans" to "번역문 수정", "disabled" to "비활성화됨",
    )

    fun regexTypeLabel(t: String) = REGEX_TYPES.firstOrNull { it.first == t }?.second ?: t

    fun newRegex(): JsonObject = JsonObject(mapOf(
        "comment" to s(""), "in" to s(""), "out" to s(""), "type" to s("editdisplay"), "flag" to s("g"), "ableFlag" to b(false),
    ))

    /** Same field shape as the original export: disabled only written when true. */
    fun regex(base: JsonObject, comment: String, inp: String, out: String, type: String, flag: String, ableFlag: Boolean, disabled: Boolean) =
        base.with("comment" to s(comment), "in" to s(inp), "out" to s(out), "type" to s(type), "flag" to s(flag), "ableFlag" to b(ableFlag),
            "disabled" to if (disabled) b(true) else null)

    fun isBlankRegex(o: JsonObject) = str(o, "comment").isBlank() && str(o, "in").isBlank() && str(o, "out").isBlank()

    // ---------- 트리거 (Lua / V2) ----------

    enum class TriggerMode { Lua, V2 }
    data class Trigger(val mode: TriggerMode, val code: String)

    /** The original's import rule (1042 v()). */
    fun trigger(data: JsonObject?): Trigger {
        val list = (Fields.get(data, TRIGGER_PATH) as? JsonArray)?.toList() ?: return Trigger(TriggerMode.Lua, "")
        if (list.isEmpty()) return Trigger(TriggerMode.Lua, "")
        val first = list[0] as? JsonObject
        val firstEffect = ((first?.get("effect") as? JsonArray)?.getOrNull(0)) as? JsonObject
        if (str(firstEffect, "type") == "v2Header" && str(firstEffect, "code").isBlank() &&
            list.drop(1).all { ((it as? JsonObject)?.get("effect") as? JsonArray).isNullOrEmpty() }) return Trigger(TriggerMode.V2, "")
        val allLua = list.all { t ->
            val eff = (t as? JsonObject)?.get("effect") as? JsonArray
            eff != null && eff.isNotEmpty() && eff.all { str(it as? JsonObject, "type").let { ty -> ty == "triggerlua" || ty == "triggercode" } }
        }
        if (allLua) return Trigger(TriggerMode.Lua, list.flatMap { t -> ((t as JsonObject)["effect"] as JsonArray).map { str(it as? JsonObject, "code") } }.joinToString("\n\n"))
        return Trigger(TriggerMode.V2, pretty.encodeToString(JsonElement.serializer(), JsonArray(list)))
    }

    /** V2 code must be a JSON array or object; null = not valid. */
    fun parseV2(code: String): JsonArray? = runCatching {
        when (val e = Json.parseToJsonElement(code)) {
            is JsonArray -> e
            is JsonObject -> JsonArray(listOf(e))
            else -> null
        }
    }.getOrNull()

    /** The original's export rule (1653 e()). */
    fun triggerJson(t: Trigger, lowLevel: Boolean): JsonArray {
        fun obj(vararg p: Pair<String, JsonElement>) = JsonObject(mapOf(*p))
        if (t.code.isBlank()) return if (t.mode == TriggerMode.Lua)
            JsonArray(listOf(obj("comment" to s(""), "type" to s("start"), "conditions" to JsonArray(emptyList()),
                "effect" to JsonArray(listOf(obj("type" to s("triggerlua"), "code" to s("")))))))
        else JsonArray(listOf(
            obj("comment" to s(""), "type" to s("manual"), "conditions" to JsonArray(emptyList()),
                "effect" to JsonArray(listOf(obj("type" to s("v2Header"), "code" to s(""), "indent" to JsonPrimitive(0))))),
            obj("comment" to s("New Event"), "type" to s("manual"), "conditions" to JsonArray(emptyList()), "effect" to JsonArray(emptyList())),
        ))
        if (t.mode == TriggerMode.Lua) {
            val m = mutableMapOf<String, JsonElement>("comment" to s(""), "type" to s("start"), "conditions" to JsonArray(emptyList()),
                "effect" to JsonArray(listOf(obj("type" to s("triggerlua"), "code" to s(t.code)))))
            if (lowLevel) m["lowLevelAccess"] = b(true)
            return JsonArray(listOf(JsonObject(m)))
        }
        return parseV2(t.code) ?: JsonArray(listOf(obj("comment" to s("SGU: invalid V2 JSON"), "type" to s("start"),
            "conditions" to JsonArray(emptyList()), "effect" to JsonArray(emptyList()))))
    }

    fun lowLevel(data: JsonObject?) = (Fields.get(data, LOW_LEVEL_PATH) as? JsonPrimitive)?.booleanOrNull == true

    fun setTrigger(data: JsonObject, t: Trigger): JsonObject = Fields.set(data, TRIGGER_PATH, triggerJson(t, lowLevel(data)))

    /** Re-write a Lua trigger after 저수준 접근 changed, so its lowLevelAccess flag matches (like the original's export). */
    fun resyncLowLevel(data: JsonObject): JsonObject {
        val t = trigger(data)
        return if (t.mode == TriggerMode.Lua && t.code.isNotBlank()) setTrigger(data, t) else data
    }

    // ---------- Bias ----------

    data class BiasRow(val text: String, val value: Double)

    fun bias(data: JsonObject?): List<BiasRow> = ((Fields.get(data, BIAS_PATH) as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { e ->
        val a = e as? JsonArray ?: return@mapNotNull null
        BiasRow((a.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: "", (a.getOrNull(1) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: 0.0)
    }

    private fun numberJson(v: Double): JsonPrimitive = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)

    fun setBias(data: JsonObject, rows: List<BiasRow>): JsonObject =
        Fields.set(data, BIAS_PATH, JsonArray(rows.map { JsonArray(listOf(s(it.text), numberJson(it.value))) }))

    // ---------- 온보딩 ----------

    fun onboarding(data: JsonObject?): JsonObject {
        val o = Fields.get(data, ONBOARDING_PATH) as? JsonObject
        val def = JsonObject(mapOf(
            "enabled" to b(false), "mode" to s("form"), "title" to s(""), "description" to s(""),
            "questions" to JsonArray(emptyList()), "html" to s(""), "floatingButton" to b(false),
        ))
        return if (o == null) def else JsonObject(def + o)
    }

    fun hasOnboarding(data: JsonObject?) = Fields.get(data, ONBOARDING_PATH).let { it != null && it !is JsonNull }

    fun newQuestion(n: Int): JsonObject = JsonObject(mapOf(
        "id" to s(newId("q")), "title" to s(""), "type" to s("single"), "varName" to s("q$n"),
        "options" to JsonArray(emptyList()), "required" to b(false),
    ))

    fun optionsText(q: JsonObject) = ((q["options"] as? JsonArray) ?: JsonArray(emptyList())).joinToString(", ") { str(it as? JsonObject, "label") }

    /** Same as the original: keep an option object when its label didn't change. */
    fun withOptions(q: JsonObject, text: String): JsonObject {
        val old = ((q["options"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
        val opts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, label ->
            old.getOrNull(i)?.takeIf { str(it, "label") == label }
                ?: JsonObject(mapOf("id" to s(newId("opt")), "label" to s(label), "value" to s(label)))
        }
        return q.with("options" to JsonArray(opts))
    }

    fun newId(prefix: String) = prefix + "_" + System.currentTimeMillis().toString(36) + (1000..9999).random().toString(36)

    // ---------- 로어북 ----------

    fun isFolder(e: JsonObject) = str(e, "mode") == "folder"
    /** A folder's own key; children point at it with `folder`. */
    fun folderKey(e: JsonObject) = ((e["keys"] as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: ""
    fun keysText(e: JsonObject, field: String = "keys") = ((e[field] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
    fun loreName(e: JsonObject) = str(e, "comment").ifBlank { str(e, "name") }
    fun caseSensitive(e: JsonObject) = bool(e, "case_sensitive") || bool(e["extensions"] as? JsonObject, "risu_case_sensitive")
    fun insertOrder(e: JsonObject) = num(e, "insertion_order")?.toInt() ?: 100

    fun lore(data: JsonObject?) = objects(data, LORE_PATH)

    private fun splitKeys(t: String) = JsonArray(t.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { s(it) })

    fun newLoreEntry(folder: String? = null): JsonObject = JsonObject(buildMap<String, JsonElement> {
        put("keys", JsonArray(emptyList())); put("content", s(""))
        put("extensions", JsonObject(mapOf("risu_case_sensitive" to b(false))))
        put("enabled", b(true)); put("insertion_order", JsonPrimitive(100)); put("constant", b(false)); put("selective", b(false))
        put("name", s("")); put("comment", s("")); put("case_sensitive", b(false)); put("use_regex", b(false)); put("mode", s("normal"))
        if (folder != null) put("folder", s(folder))
    })

    fun newLoreFolder(name: String = "새 폴더"): JsonObject {
        val key = "folder:" + newId("lore")
        return newLoreEntry().with("keys" to JsonArray(listOf(s(key))), "name" to s(name), "comment" to s(name), "mode" to s("folder"))
    }

    /** Builds an entry the way the original's export does, on top of the stored one (unknown fields kept). */
    fun loreEntry(
        base: JsonObject, name: String, keys: String, secondKeys: String, folder: String?, alwaysActive: Boolean,
        selective: Boolean, useRegex: Boolean, caseSensitive: Boolean, insertOrder: Int,
    ): JsonObject {
        val folderMode = isFolder(base)
        val ext = (base["extensions"] as? JsonObject ?: JsonObject(emptyMap())).with("risu_case_sensitive" to b(caseSensitive))
        return base.with(
            "keys" to if (folderMode) base["keys"] ?: JsonArray(emptyList()) else splitKeys(keys),
            "secondary_keys" to if (selective && !folderMode) splitKeys(secondKeys) else null,
            "extensions" to ext,
            "enabled" to (base["enabled"] ?: b(true)),
            "insertion_order" to JsonPrimitive(insertOrder),
            "constant" to b(alwaysActive),
            "selective" to b(selective),
            "name" to s(name), "comment" to s(name),
            "case_sensitive" to b(caseSensitive),
            "use_regex" to b(useRegex),
            "mode" to s(if (folderMode) "folder" else "normal"),
            "folder" to if (!folderMode && !folder.isNullOrEmpty()) s(folder) else null,
        )
    }

    fun isBlankLore(e: JsonObject) = !isFolder(e) && loreName(e).isBlank() && keysText(e).isBlank() && str(e, "content").isBlank()

    fun children(list: List<JsonObject>, folder: JsonObject) = list.filter { !isFolder(it) && str(it, "folder") == folderKey(folder) }

    /** Original syncFolders: a folder with children is "항상 활성화" exactly when all its children are. */
    fun syncFolders(list: List<JsonObject>): List<JsonObject> = list.map { e ->
        if (!isFolder(e)) return@map e
        val kids = children(list, e)
        if (kids.isEmpty()) e else {
            val all = kids.all { bool(it, "constant") }
            if (all == bool(e, "constant")) e else e.with("constant" to b(all))
        }
    }

    /** Saves entry [index]; a folder's 항상 활성화 change is pushed to all its children (original setFolderAlways). */
    fun saveLore(data: JsonObject, index: Int, entry: JsonObject): JsonObject {
        val list = lore(data).toMutableList()
        if (index !in list.indices) return data
        val old = list[index]
        list[index] = entry
        var out: List<JsonObject> = list
        if (isFolder(entry) && bool(old, "constant") != bool(entry, "constant")) {
            val key = folderKey(entry); val on = bool(entry, "constant")
            out = out.map { if (!isFolder(it) && str(it, "folder") == key) it.with("constant" to b(on)) else it }
        }
        return Fields.set(data, LORE_PATH, JsonArray(syncFolders(out)))
    }

    fun addLore(data: JsonObject, entry: JsonObject): Pair<JsonObject, Int> {
        val list = lore(data) + entry
        return Fields.set(data, LORE_PATH, JsonArray(syncFolders(list))) to list.size - 1
    }

    /** Removing a folder moves its children to the top level (original removeLoreEntry). */
    fun removeLore(data: JsonObject, index: Int): JsonObject {
        val list = lore(data)
        val gone = list.getOrNull(index) ?: return data
        val key = if (isFolder(gone)) folderKey(gone) else null
        val rest = list.filterIndexed { i, _ -> i != index }.map { if (key != null && str(it, "folder") == key) it.with("folder" to null) else it }
        return Fields.set(data, LORE_PATH, JsonArray(syncFolders(rest)))
    }

    fun setFolderAlways(data: JsonObject, index: Int, on: Boolean): JsonObject {
        val e = lore(data).getOrNull(index) ?: return data
        return saveLore(data, index, e.with("constant" to b(on)))
    }

    fun setListAt(data: JsonObject, path: List<String>, index: Int, item: JsonObject?): JsonObject {
        val list = objects(data, path).toMutableList()
        if (index !in list.indices) return data
        if (item == null) list.removeAt(index) else list[index] = item
        return Fields.set(data, path, JsonArray(list))
    }

    fun append(data: JsonObject, path: List<String>, item: JsonObject): Pair<JsonObject, Int> {
        val list = objects(data, path) + item
        return Fields.set(data, path, JsonArray(list)) to list.size - 1
    }
}
