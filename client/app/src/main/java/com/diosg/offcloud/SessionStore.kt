package com.diosg.offcloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda el token de sesión cifrado con una clave AES del Android Keystore
 * (la clave vive en el hardware del móvil y no se puede extraer). Es lo que
 * recomienda Google desde que deprecó EncryptedSharedPreferences.
 *
 * Los workers leen el token de aquí al ejecutarse, en vez de recibirlo en
 * su inputData: WorkManager guarda el inputData sin cifrar en su propia BD,
 * y así además siempre usan la sesión actual aunque se haya vuelto a entrar.
 */
object SessionStore {

    private const val PREFS = "offcloud_prefs"
    private const val KEY_TOKEN_ENC = "token_enc"
    private const val LEGACY_KEY_TOKEN = "token" // versiones anteriores: texto plano
    private const val KEY_ALIAS = "offcloud_session_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    fun getToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Migración: si queda un token en texto plano, se cifra y se borra.
        prefs.getString(LEGACY_KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { legacy ->
            setToken(context, legacy)
            prefs.edit().remove(LEGACY_KEY_TOKEN).apply()
            return legacy
        }

        val stored = prefs.getString(KEY_TOKEN_ENC, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_SIZE)
            )
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            // Clave perdida (p.ej. prefs restauradas de un backup en otro
            // móvil: la clave del Keystore no viaja). Toca volver a entrar.
            android.util.Log.e("OffCloud", "No se pudo descifrar el token: ${e.message}", e)
            clearToken(context)
            null
        }
    }

    fun setToken(context: Context, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN_ENC, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TOKEN_ENC)
            .remove(LEGACY_KEY_TOKEN)
            .apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
