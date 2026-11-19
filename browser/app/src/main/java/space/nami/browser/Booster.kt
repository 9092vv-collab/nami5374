package space.nami.browser

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import rikka.shizuku.Shizuku

object Booster {

    fun isShizukuRunning(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasShizukuPermission(): Boolean =
        runCatching {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun shizukuUid(): Int =
        runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun requestShizukuPermission(code: Int = 9001) {
        runCatching {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(code)
            }
        }
    }

    fun runShizuku(cmd: String): Pair<Int, String> {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val proc = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as java.lang.Process
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            val err = proc.errorStream.bufferedReader().use { it.readText() }.trim()
            val code = proc.waitFor()
            val combined = buildString {
                if (out.isNotEmpty()) append(out)
                if (err.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(err)
                }
            }
            code to combined
        } catch (t: Throwable) {
            -1 to (t.message ?: t.toString())
        }
    }

    fun hasRoot(): Boolean {
        val (code, out) = runRoot("id")
        return code == 0 && out.contains("uid=0")
    }

    fun runRoot(cmd: String): Pair<Int, String> {
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = proc.waitFor()
            code to out
        } catch (t: Throwable) {
            -1 to (t.message ?: t.toString())
        }
    }

    fun runCmd(
        cmd: String,
        useRoot: Boolean,
        useShizuku: Boolean,
        requireRoot: Boolean = false
    ): Pair<Int, String> {
        if (useRoot && hasRoot()) {
            return runRoot(cmd)
        }
        if (useShizuku && hasShizukuPermission()) {
            if (!requireRoot || shizukuUid() == 0) {
                return runShizuku(cmd)
            }
        }
        return -1 to if (requireRoot) "Root permission required" else "No privileged permission"
    }

    fun compileAot(ctx: Context, useRoot: Boolean, useShizuku: Boolean): String {
        val pkgs = linkedSetOf("space.nami.browser")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.packageName?.let { pkgs.add(it) }
        }
        return pkgs.joinToString("\n") { pkg ->
            val (code, out) = runCmd("cmd package compile -m speed -f $pkg", useRoot, useShizuku)
            "$pkg: [$code] $out"
        }
    }

    fun disableBatteryLimits(ctx: Context, useRoot: Boolean, useShizuku: Boolean): String {
        val pkg = "space.nami.browser"
        val cmds = listOf(
            "dumpsys deviceidle whitelist +$pkg",
            "am set-standby-bucket $pkg active",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow"
        )
        return cmds.joinToString("\n") { cmd ->
            val (code, out) = runCmd(cmd, useRoot, useShizuku)
            "$cmd -> [$code] $out"
        }
    }

    fun boostOomAndPriority(ctx: Context, useRoot: Boolean, useShizuku: Boolean): String {
        val myPid = android.os.Process.myPid()
        val script = """
            echo -800 > /proc/$myPid/oom_score_adj 2>/dev/null || true
            renice -n -10 -p $myPid 2>/dev/null || true
            for p in /proc/[0-9]*; do
                pid=${'$'}{p##*/}
                [ "${'$'}pid" = "$myPid" ] && continue
                cmd=$(tr '\0' ' ' < "${'$'}p/cmdline" 2>/dev/null)
                case "${'$'}cmd" in
                    *space.nami.browser*)
                        echo -500 > "${'$'}p/oom_score_adj" 2>/dev/null || true
                        ;;
                esac
            done
            echo "OK pid=$myPid"
        """.trimIndent()
        val (code, out) = runCmd(script, useRoot, useShizuku, requireRoot = true)
        return "[$code] $out"
    }

    fun mountRamCache(ctx: Context, useRoot: Boolean, useShizuku: Boolean, sizeMb: Int = 256): String {
        val cachePath = "${ctx.cacheDir.absolutePath}/WebView/Default/HTTP Cache"
        val escapedPath = cachePath.replace(" ", "\\040")
        val uid = android.os.Process.myUid()
        val script = """
            if grep -qF "$escapedPath" /proc/mounts 2>/dev/null; then
                echo "already mounted: $cachePath"
            else
                mkdir -p "$cachePath" &&
                mount -t tmpfs -o size=${sizeMb}M,mode=0771 tmpfs "$cachePath" &&
                chown -R $uid:$uid "$cachePath" &&
                echo "mounted ${sizeMb}M tmpfs at $cachePath"
            fi
        """.trimIndent()
        val (code, out) = runCmd(script, useRoot, useShizuku, requireRoot = true)
        return "[$code] $out"
    }
}
