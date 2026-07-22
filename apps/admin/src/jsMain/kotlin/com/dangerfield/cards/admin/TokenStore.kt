package com.dangerfield.cards.admin

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * Admin tokens + actor name, kept in this browser's localStorage. Keys are
 * per target environment (baseUrl), so the console served by prod can also
 * hold a dev token for the read-only diff view. Storage is per console
 * origin and readable by any JS on it — acceptable here: the page loads no
 * third-party scripts and Compose HTML escapes all text nodes.
 */
internal object TokenStore {
    private const val TOKEN_PREFIX = "cards-admin.token."
    private const val ACTOR_KEY = "cards-admin.actor"

    fun token(env: AdminEnv): String? =
        localStorage[TOKEN_PREFIX + env.baseUrl]?.takeIf { it.isNotBlank() }

    fun saveToken(env: AdminEnv, token: String) {
        localStorage[TOKEN_PREFIX + env.baseUrl] = token
    }

    fun forgetToken(env: AdminEnv) {
        localStorage.removeItem(TOKEN_PREFIX + env.baseUrl)
    }

    var actor: String?
        get() = localStorage[ACTOR_KEY]?.takeIf { it.isNotBlank() }
        set(value) {
            if (value.isNullOrBlank()) localStorage.removeItem(ACTOR_KEY) else localStorage[ACTOR_KEY] = value
        }
}
