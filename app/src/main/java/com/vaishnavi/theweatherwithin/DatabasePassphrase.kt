package com.vaishnavi.theweatherwithin

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Generates, and securely stores, the passphrase that encrypts the local
 * database. The passphrase itself is a random 32-byte key — never a
 * human-typed password — stored via EncryptedSharedPreferences, which is
 * itself protected by a hardware-backed Android Keystore key on most
 * devices. The raw passphrase is never hardcoded anywhere in the app.
 */
object DatabasePassphrase {

    private const val PREFS_NAME = "secure_db_prefs"
    private const val KEY_NAME = "db_passphrase_hex"

    fun getOrCreate(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val existingHex = prefs.getString(KEY_NAME, null)
        if (existingHex != null) {
            return hexToBytes(existingHex)
        }

        val newKey = ByteArray(32)
        SecureRandom().nextBytes(newKey)
        prefs.edit().putString(KEY_NAME, bytesToHex(newKey)).apply()
        return newKey
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}