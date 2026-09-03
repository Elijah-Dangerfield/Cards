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
        "https://apps.apple.com/app/id$APP_STORE_ID?action=write-review"
    } else {
        "https://play.google.com/store/apps/details?id=${BuildInfo.applicationId}"
    }

/**
 * Downcard's numeric App Store id, from the App Store Connect build
 * notification (App Apple ID 6788423648). Previously a placeholder, which meant
 * the iOS review link resolved to nothing.
 */
private const val APP_STORE_ID = "6788423648"

/**
 * The plain store listing, for "a newer version is available". Same target as
 * [storeReviewUrl] minus the write-review action: we want the Update button,
 * not the review sheet.
 */
internal fun storeListingUrl(): String =
    if (BuildInfo.isiOS()) {
        "https://apps.apple.com/app/id$APP_STORE_ID"
    } else {
        "https://play.google.com/store/apps/details?id=${BuildInfo.applicationId}"
    }
