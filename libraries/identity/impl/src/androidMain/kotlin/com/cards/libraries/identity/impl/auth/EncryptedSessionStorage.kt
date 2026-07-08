package com.dangerfield.cards.libraries.identity.impl.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dangerfield.cards.libraries.identity.auth.SecureSessionStorage
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * [SecureSessionStorage] backed by [EncryptedSharedPreferences] with an
 * AndroidKeyStore-held AES-256 master key, so the Supabase refresh token
 * never sits in plaintext prefs (AUTH-16). Lazy because building the master
 * key touches the KeyStore — that cost belongs on the first session
 * read/write (already off-main via the session manager), not DI graph
 * construction.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class EncryptedSessionStorage(
    private val context: Context,
) : SecureSessionStorage {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val FILE_NAME = "cards_secure_session"
    }
}
