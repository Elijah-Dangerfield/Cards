package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.identity.auth.SecureSessionStorage
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Supabase [SessionManager] that keeps the session in OS-encrypted storage
 * (Keychain / EncryptedSharedPreferences via [SecureSessionStorage]) instead
 * of supabase-kt's default plaintext `multiplatform-settings` store (AUTH-16;
 * decisions.md 2026-05-18 accepted plaintext only until claim shipped).
 *
 * A session already sitting in the old plaintext store migrates on the first
 * load after the upgrade: read old once, write to the encrypted store, clear
 * the old — nobody gets signed out. [legacy] is the plaintext-store manager
 * pointed at supabase-kt's default key; [deleteSession] clears it too so
 * sign-out never leaves a resurrectable plaintext copy behind.
 *
 * `encodeDefaults = true` matches supabase-kt's own session serialization —
 * `UserSession.expiresAt` is a defaulted property, and dropping it would
 * reset the expiry window on every load.
 */
class SecureSessionManager(
    private val storage: SecureSessionStorage,
    private val key: String,
    private val legacy: SessionManager?,
    private val dispatchers: DispatcherProvider,
    private val json: Json = defaultJson,
) : SessionManager {

    override suspend fun saveSession(session: UserSession) = withContext(dispatchers.io) {
        storage.write(key, json.encodeToString(session))
    }

    override suspend fun loadSession(): UserSession = withContext(dispatchers.io) {
        storage.read(key)?.let { return@withContext json.decodeFromString<UserSession>(it) }
        val migrated = legacy?.loadSessionOrNull()
            ?: error("No session stored under $key")
        storage.write(key, json.encodeToString(migrated))
        legacy.deleteSession()
        migrated
    }

    override suspend fun deleteSession(): Unit = withContext(dispatchers.io) {
        storage.delete(key)
        legacy?.deleteSession()
    }

    companion object {
        private val defaultJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /**
         * The per-project storage key, byte-for-byte the key supabase-kt's
         * default `SettingsSessionManager` derives — the migration reads the
         * legacy plaintext entry under this exact name, and keying by project
         * URL keeps dev/prod sessions apart once the env split lands.
         */
        fun storageKeyFor(supabaseUrl: String): String {
            val host = supabaseUrl.split("//").last()
                .removeSuffix("/")
                .replace('/', '-')
                .replace('.', '-')
            return "sb-$host-session"
        }
    }
}
