package com.dangerfield.cards.features.home.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.isiOS

/**
 * Deep link for leaving a store review. We open the store listing directly
 * rather than the platform in-app review prompt (`SKStoreReviewController` /
 * Play `ReviewManager`): the OS throttles those and may silently show nothing,
 * which is the wrong behavior for a button the user explicitly tapped.
 *
 * Android points at the Play listing. iOS points at the App Store write-review
 * sheet, which needs the numeric App Store id.
 */
internal fun storeReviewUrl(): String =
    if (BuildInfo.isiOS()) {
        // TODO(review): the iOS App Store listing doesn't exist yet, so there's
        //  no real numeric id to deep-link to. Swap APP_STORE_ID_PLACEHOLDER for
        //  the real App Store id at iOS launch — until then this link won't
        //  resolve. See docs/todo.md (ENG store-review deep link).
        "https://apps.apple.com/app/id$APP_STORE_ID_PLACEHOLDER?action=write-review"
    } else {
        "https://play.google.com/store/apps/details?id=${BuildInfo.applicationId}"
    }

private const val APP_STORE_ID_PLACEHOLDER = "0000000000"
