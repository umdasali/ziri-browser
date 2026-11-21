package com.ziribrowser.passwords

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The key that seals saved passwords. It is created inside the Android Keystore (hardware-backed where the phone has
 * it) and can't be copied out, so a stolen database or backup can't be opened elsewhere.
 */
object Vault {
    private const val ALIAS = "ziri_vault_v1"
    private const val PROVIDER = "AndroidKeyStore"

    @Synchronized // two first-time callers must not each create the key
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    val box: SecretBox = AesGcmBox { key() }
}
