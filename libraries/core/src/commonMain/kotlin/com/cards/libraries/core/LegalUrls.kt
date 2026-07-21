package com.dangerfield.cards.libraries.core

/**
 * Outbound legal + safety links the client hands off to the system browser
 * (via `Router.openWebLink`). One source of truth so onboarding consent and
 * the Settings rows can't drift apart.
 *
 * [PRIVACY_POLICY] / [TERMS_OF_SERVICE] / [SUPPORT] are served by the Astro site
 * in `website/`, built and published to GitHub Pages at the custom domain
 * downcard.app on push to main by `.github/workflows/pages.yml`. These constants
 * are the single update point. [SUPPORT] is the public support URL both app
 * stores require on the listing.
 *
 * [RESPONSIBLE_PLAY] points at the National Council on Problem Gambling
 * (1-800-GAMBLER). Cards is play-money with no cash-out, but it simulates
 * gambling and sells chip packs for real money, so a visible path to help is
 * both good practice and expected of simulated-gambling apps.
 */
object LegalUrls {
    const val PRIVACY_POLICY: String = "https://downcard.app/privacy"
    const val TERMS_OF_SERVICE: String = "https://downcard.app/terms"
    const val SUPPORT: String = "https://downcard.app/support"
    const val RESPONSIBLE_PLAY: String = "https://www.ncpgambling.org/"

    /**
     * Version of the Terms / Privacy a fresh "by continuing, you agree" pass
     * records as accepted. Bump on every material change to the hosted Terms
     * or Privacy so a re-consent (and a fresh acceptance record) can be
     * required — the persisted acceptance ([AppData.acceptedLegalVersion])
     * carries this number, so comparing it against the live version is the
     * trigger.
     */
    const val LEGAL_VERSION: Int = 2
}
