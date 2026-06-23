package com.dangerfield.cards.libraries.core

/**
 * Outbound legal + safety links the client hands off to the system browser
 * (via `Router.openWebLink`). One source of truth so onboarding consent and
 * the Settings rows can't drift apart.
 *
 * [PRIVACY_POLICY] / [TERMS_OF_SERVICE] are published from `pages/` on push to
 * main by `.github/workflows/pages.yml`; swap to a custom domain by dropping a
 * CNAME into `pages/` and editing these constants — the single update point.
 *
 * [RESPONSIBLE_PLAY] points at the National Council on Problem Gambling
 * (1-800-GAMBLER). Cards is play-money with no cash-out, but it simulates
 * gambling and sells chip packs for real money, so a visible path to help is
 * both good practice and expected of simulated-gambling apps.
 */
object LegalUrls {
    const val PRIVACY_POLICY: String = "https://elijah-dangerfield.github.io/Cards/privacy.html"
    const val TERMS_OF_SERVICE: String = "https://elijah-dangerfield.github.io/Cards/terms.html"
    const val RESPONSIBLE_PLAY: String = "https://www.ncpgambling.org/"
}
