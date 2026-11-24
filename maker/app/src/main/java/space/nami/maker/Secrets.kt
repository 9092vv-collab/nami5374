package space.nami.maker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keys, encrypted with an Android Keystore AES key (same as Nami next). */
object Secrets {
    private const val ALIAS = "nami_maker_secrets"
    private var file: File? = null
    private val enc = mutableMapOf<String, String>()

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "secrets.json"); file = f
        runCatching { val o = JSONObject(f.readText()); o.keys().forEach { k -> enc[k] = o.getString(k) } }
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

    fun has(id: String) = enc.containsKey(id)

    @Synchronized
    fun remove(id: String) { if (enc.remove(id) != null) save() }

    private fun save() {
        val o = JSONObject(); enc.forEach { (k, v) -> o.put(k, v) }
        file?.writeText(o.toString())
    }
}
