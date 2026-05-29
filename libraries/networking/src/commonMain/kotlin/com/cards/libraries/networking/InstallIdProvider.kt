package com.dangerfield.cards.libraries.networking

/**
 * Per-installation identifier the client attaches to every authenticated
 * request via the `X-Install-Id` header (see [ClientHeaders.HEADER_INSTALL_ID]).
 *
 * The id is a UUID generated once on first launch and persisted locally; it
 * regenerates on reinstall (app-local storage is wiped). The server uses it
 * for L1 orphan-account cleanup — see
 * [docs/recovery-and-orphaned-accounts.md](https://github.com/Elijah-Dangerfield/Cards/blob/main/docs/recovery-and-orphaned-accounts.md).
 *
 * Implementations MUST be cheap and non-suspending — they're called on every
 * outgoing request. The expected shape is "hydrate once at boot, cache in
 * memory, return the cached value." Returning `null` is allowed and means
 * "header omitted" — the server treats absence as "client too old to send
 * one." See `CachedInstallIdProvider` in `:libraries:cards:impl` for the
 * production impl.
 */
interface InstallIdProvider {
    /**
     * Canonical UUID string for this install, or `null` if not yet
     * available (e.g. AppCache hydration hasn't completed on this boot).
     */
    fun current(): String?
}
