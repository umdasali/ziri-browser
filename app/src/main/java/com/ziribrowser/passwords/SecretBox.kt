package com.ziribrowser.passwords

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens short secrets. */
interface SecretBox {
    fun seal(plain: String): String
    /** The original text, or null if the data was changed or the key is gone. */
    fun open(sealed: String): String?
}

/** AES-256-GCM: each value gets its own random nonce, and any change to the stored text makes opening fail. */
class AesGcmBox(private val key: () -> SecretKey) : SecretBox {
    override fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        // The cipher picks the nonce itself: the Android Keystore refuses nonces chosen by the app.
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        check(iv.size == 12) { "unexpected nonce size" }
        val data = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().withoutPadding().encodeToString(iv + data)
    }

    override fun open(sealed: String): String? = runCatching {
        val all = Base64.getDecoder().decode(sealed)
        if (all.size < 13) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }.getOrNull()
}
