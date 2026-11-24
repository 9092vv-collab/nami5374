package space.nami.maker

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** PC판: 키를 이 PC 사용자 폴더의 AES 키 파일로 암호화해 저장 (폰판은 안드로이드 키스토어). Nami next PC판과 같은 방식. */
object Secrets {
    private var file: File? = null
    private var keyFile: File? = null
    private val enc = mutableMapOf<String, String>()

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "secrets.json"); file = f
        keyFile = File(ctx.filesDir, "secrets.key")
        runCatching { val o = JSONObject(f.readText()); o.keys().forEach { k -> enc[k] = o.getString(k) } }
    }

    private fun key(): SecretKeySpec {
        val k = keyFile!!
        if (!k.exists()) k.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
        return SecretKeySpec(k.readBytes(), "AES")
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    @Synchronized
    fun put(id: String, value: String) {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        enc[id] = b64(iv) + ":" + b64(c.doFinal(value.toByteArray(Charsets.UTF_8)))
        save()
    }

    @Synchronized
    fun get(id: String?): String? {
        val v = enc[id ?: return null] ?: return null
        return runCatching {
            val (iv, data) = v.split(":", limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
            String(c.doFinal(unb64(data)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun has(id: String) = enc.containsKey(id)

    @Synchronized
    fun remove(id: String) { if (enc.remove(id) != null) save() }

    private fun save() {
        val o = JSONObject(); enc.forEach { (k, v) -> o.put(k, v) }
        file?.writeText(o.toString())
    }
}
