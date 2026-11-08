package space.sgu.next.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys, encrypted with an Android Keystore AES key (never leaves the device's secure hardware). */
object Secrets {
    private const val ALIAS = "sgu_next_secrets"
    private lateinit var file: File
    private val enc = mutableMapOf<String, String>()

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, "secrets.json")
        runCatching {
            CardIO.json.parseToJsonElement(file.readText()).jsonObject.forEach { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { enc[k] = it }
            }
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    @Synchronized
    fun put(id: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        enc[id] = b64(c.iv) + ":" + b64(c.doFinal(value.toByteArray(Charsets.UTF_8)))
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
    fun remove(id: String) {
        if (enc.remove(id) != null) save()
    }

    private fun save() {
        val obj = JsonObject(enc.mapValues { JsonPrimitive(it.value) })
        file.writeText(CardIO.json.encodeToString(JsonElement.serializer(), obj))
    }
}
