package space.nami.tweaks

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import rikka.shizuku.Shizuku

/** Runs shell commands as the shell user through Shizuku. */
object Sh {
    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
    }

    /** Output of a command, or null. Blocking: call off the main thread. */
    fun out(vararg cmd: String): String? {
        if (!ready()) return null
        return runCatching {
            val p = newProcess.invoke(null, arrayOf(*cmd), null, null) as Process
            val o = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (p.exitValue() == 0) o.trim() else null
        }.getOrNull()
    }

    /** null = worked, otherwise a short reason. Blocking: call off the main thread. */
    fun run(vararg cmd: String): String? {
        if (!ready()) return "Shizuku가 연결되어 있지 않아요"
        return runCatching {
            val p = newProcess.invoke(null, arrayOf(*cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            if (p.exitValue() == 0 && !out.contains("Exception") && !err.contains("Exception")) null
            else (err.ifBlank { out }).trim().take(160).ifBlank { "실패(${p.exitValue()})" }
        }.getOrElse {
            val e = (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it
            "${e.javaClass.simpleName}: ${e.message?.take(120)}"
        }
    }
}

/** Window / transition / animator duration scales (developer options), all set together. */
object Anim {
    private val KEYS = listOf(
        Settings.Global.WINDOW_ANIMATION_SCALE,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        Settings.Global.ANIMATOR_DURATION_SCALE,
    )

    fun read(c: Context): List<Float> = KEYS.map { Settings.Global.getFloat(c.contentResolver, it, 1f) }

    /** Remembers what it was before the first change, for "원래대로". */
    fun set(c: Context, v: Float): String? {
        val p = c.getSharedPreferences("tweaks", Context.MODE_PRIVATE)
        if (!p.contains("anim0")) {
            val now = read(c)
            p.edit().putFloat("anim0", now[0]).putFloat("anim1", now[1]).putFloat("anim2", now[2]).apply()
        }
        return setAll(listOf(v, v, v))
    }

    fun original(c: Context): List<Float>? {
        val p = c.getSharedPreferences("tweaks", Context.MODE_PRIVATE)
        if (!p.contains("anim0")) return null
        return listOf(p.getFloat("anim0", 1f), p.getFloat("anim1", 1f), p.getFloat("anim2", 1f))
    }

    fun restore(c: Context): String? {
        val o = original(c) ?: return null
        val r = setAll(o)
        if (r == null) c.getSharedPreferences("tweaks", Context.MODE_PRIVATE).edit()
            .remove("anim0").remove("anim1").remove("anim2").apply()
        return r
    }

    private fun setAll(v: List<Float>): String? {
        KEYS.forEachIndexed { i, k ->
            Sh.run("settings", "put", "global", k, v[i].toString())?.let { return it }
        }
        return null
    }
}

/** Samsung screen mode (system screen_mode_setting): 0 AMOLED cinema, 1 AMOLED photo, 2 basic, 3 natural, 4 vivid. */
object ScreenMode {
    val MODES = listOf(4 to "선명하게", 3 to "자연스럽게", 0 to "AMOLED 시네마", 1 to "AMOLED 사진", 2 to "기본")

    /** Read through the shell: apps can't read Samsung's own settings keys directly. */
    fun read(): Int? = Sh.out("settings", "get", "system", "screen_mode_setting")?.toIntOrNull()

    fun set(v: Int): String? = Sh.run("settings", "put", "system", "screen_mode_setting", v.toString())
}

/**
 * Samsung "부드러운 모션 및 화면 전환": secure `refresh_rate_mode` 0 = 일반(60Hz), 1 = 최적화(적응형, 최대 120Hz),
 * 2 = 높음(120Hz 고정, old One UI value that's no longer in the menu). For 높음 we also pin the AOSP
 * minimum refresh rate to 120 in case the device ignores mode 2; any other choice removes that pin.
 */
object RefreshRate {
    val MODES = listOf(0 to "일반", 1 to "최적화", 2 to "120 고정")

    fun read(): Int? = Sh.out("settings", "get", "secure", "refresh_rate_mode")?.trim()?.toIntOrNull()

    fun minPinned(): Boolean =
        Sh.out("settings", "get", "system", "min_refresh_rate")?.trim()?.toFloatOrNull()?.let { it >= 119f } == true

    /** For finding the real keys on this device: every setting whose name mentions refresh/hz/fps, plus display state. */
    fun dump(): String {
        val re = Regex("refresh|hz|fps|frame_rate|framerate", RegexOption.IGNORE_CASE)
        val sb = StringBuilder()
        for (ns in listOf("secure", "system", "global")) {
            Sh.out("settings", "list", ns)?.lineSequence()
                ?.filter { re.containsMatchIn(it.substringBefore('=')) }
                ?.forEach { sb.append(ns).append(' ').append(it).append('\n') }
        }
        Sh.out("dumpsys", "display")?.lineSequence()
            ?.map { it.trim() }
            ?.filter { Regex("RefreshRate|mDisplayModeSpecs|mActiveMode|modeId|SupportedModes", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?.distinct()?.take(40)
            ?.forEach { sb.append("display ").append(it.take(300)).append('\n') }
        return sb.toString().ifBlank { "(못 읽었어요 — Shizuku 연결 확인)" }
    }

    fun set(v: Int): String? {
        val err = Sh.run("settings", "put", "secure", "refresh_rate_mode", v.toString())
        if (err != null) return err
        return if (v == 2) Sh.run("settings", "put", "system", "min_refresh_rate", "120.0")
        else Sh.run("settings", "delete", "system", "min_refresh_rate")
    }
}

/**
 * Safe-to-disable ratings from Universal Android Debloater Next Generation (GPL-3.0,
 * github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation),
 * Samsung/Google/AOSP part only, bundled as assets/uad.json: pkg -> [R|A|E|U, description].
 */
object Uad {
    @Volatile private var map: Map<String, Pair<Int, String>>? = null

    /** Packages whose rating and (Korean) description Claude wrote, because UAD doesn't list them (assets/extra.json). */
    @Volatile var byClaude: Set<String> = emptySet()
        private set

    private fun read(c: Context, file: String, into: HashMap<String, Pair<Int, String>>, onlyNew: Boolean): Set<String> {
        val added = HashSet<String>()
        runCatching {
            val o = org.json.JSONObject(c.assets.open(file).bufferedReader().readText())
            o.keys().forEach { k ->
                if (onlyNew && into.containsKey(k)) return@forEach
                val a = o.getJSONArray(k)
                val lv = when (a.getString(0)) { "R" -> 0; "A" -> 1; "E" -> 2; else -> 3 }
                into[k] = lv to a.getString(1)
                added += k
            }
        }
        return added
    }

    fun get(c: Context): Map<String, Pair<Int, String>> = map ?: synchronized(this) {
        map ?: HashMap<String, Pair<Int, String>>(5000).let { m ->
            read(c, "uad.json", m, false)
            byClaude = read(c, "extra.json", m, true)
            m as Map<String, Pair<Int, String>>
        }.also { map = it }
    }

    val NAMES = listOf("추천", "고급", "전문가", "위험")
    val COLORS = listOf(0xFF2F8F5BL, 0xFFB7791FL, 0xFFD97757L, 0xFFC0392BL)
    val MEANING = listOf(
        "안 쓰는 기능이라면 꺼도 대부분 문제없어요.",
        "꺼도 되지만, 관련된 기능이 같이 안 될 수 있어요.",
        "무슨 앱인지 알 때만 끄세요. 다른 기능이 망가질 수 있어요.",
        "끄면 폰이 제대로 안 돌아갈 수 있어서 막아 뒀어요.",
    )
}

data class AppRow(
    val pkg: String, val label: String, val system: Boolean, val enabled: Boolean,
    /** 0 추천, 1 고급, 2 전문가, 3 위험, null = not in the list. */
    val level: Int? = null, val desc: String? = null,
    /** Rating/description written by Claude (not in UAD). */
    val byClaude: Boolean = false,
) {
    /** Knox / payment / secure folder: Samsung Pay, Wallet, Pass and Secure Folder need these. */
    val payment get() = listOf("knox", "spay", "samsungpass", "wallet", "securefolder", "authfw", "skms", "biometric", "android.ese", "ssiframework", "skpm")
        .any { pkg.contains(it, true) }
    /** Can't be switched off here. */
    val locked get() = essential || level == 3
    val samsung get() = pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.") || pkg.startsWith("com.samsung")
    /** Turning these off can leave the phone unusable (no home screen, no keyboard, can't install, ...). */
    val essential get() = pkg in ESSENTIAL || ESSENTIAL_PREFIX.any { pkg.startsWith(it) }

    companion object {
        private val ESSENTIAL = setOf(
            "android", "com.android.systemui", "com.android.settings", "com.sec.android.app.launcher",
            "com.android.phone", "com.android.server.telecom", "com.samsung.android.incallui",
            "com.samsung.android.dialer", "com.samsung.android.app.telephonyui", "com.android.shell",
            "com.google.android.webview", "com.android.webview",
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.samsung.android.honeyboard", "com.sec.android.inputmethod",
            "com.samsung.android.biometrics.app.setting", "com.android.bluetooth",
            "com.android.networkstack", "com.google.android.networkstack",
            "com.sec.android.app.SecSetupWizard", "com.google.android.setupwizard",
            "com.google.android.gms", "com.google.android.gsf", "com.samsung.android.kgclient",
            "com.samsung.android.knox.attestation", "com.sec.android.app.firewall",
            "com.samsung.android.providers.context", "com.android.externalstorage",
            "com.android.documentsui", "com.google.android.documentsui",
            "moe.shizuku.privileged.api", "space.nami.tweaks",
        )
        private val ESSENTIAL_PREFIX = listOf(
            "com.android.providers.", "com.google.android.providers.",
            "com.samsung.android.providers.", "com.sec.android.provider.",
            "com.android.inputmethod", "com.samsung.android.knox.",
        )
    }
}

object Apps {
    fun load(c: Context): List<AppRow> {
        val pm = c.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS.toLong() or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS.toLong()
        val uad = Uad.get(c)
        return pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags)).map { ai ->
            val u = uad[ai.packageName]
            AppRow(
                level = u?.first,
                desc = u?.second,
                byClaude = ai.packageName in Uad.byClaude,
                pkg = ai.packageName,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                enabled = isOn(pm, ai.packageName),
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun isOn(pm: PackageManager, pkg: String): Boolean = runCatching {
        when (pm.getApplicationEnabledSetting(pkg)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(
                PackageManager.MATCH_DISABLED_COMPONENTS.toLong())).enabled
        }
    }.getOrDefault(true)

    fun setOn(pkg: String, on: Boolean): String? =
        if (on) Sh.run("pm", "enable", "--user", "0", pkg)
        else Sh.run("pm", "disable-user", "--user", "0", pkg)
}
