package space.sgu.next.data

import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** PC판: API 키를 이 PC의 사용자 폴더에 둔 AES 키로 암호화해 저장 (안드로이드판은 키스토어). */
object Secrets {
    private lateinit var file: File
    private lateinit var keyFile: File
    private val enc = mutableMapOf<String, String>()

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "secrets.json")
        keyFile = File(ctx.filesDir, "secrets.key")
        runCatching {
            CardIO.json.parseToJsonElement(file.readText()).jsonObject.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { enc[k] = it } }
        }
    }

    private fun key(): SecretKeySpec {
        if (!keyFile.exists()) keyFile.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
        return SecretKeySpec(keyFile.readBytes(), "AES")
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

    @Synchronized
    fun remove(id: String) { if (enc.remove(id) != null) save() }

    private fun save() {
        file.writeText(CardIO.json.encodeToString(JsonElement.serializer(), JsonObject(enc.mapValues { JsonPrimitive(it.value) })))
    }
}
