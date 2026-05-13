@file:OptIn(ExperimentalObjCName::class)

package com.dangerfield.cards.libraries.cards

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

@ObjCName("Permission", exact = true)
sealed class Permission {
    @ObjCName("PermissionNotifications", exact = true)
    data object Notifications : Permission()
}

@ObjCName("PermissionStatus", exact = true)
enum class PermissionStatus {
    GRANTED, DENIED, NOT_DETERMINED
}

@ObjCName("PermissionResult", exact = true)
sealed class PermissionResult {
    @ObjCName("PermissionResultGranted", exact = true)
    data object Granted : PermissionResult()
    @ObjCName("PermissionResultDenied", exact = true)
    data class Denied(val canRequestAgain: Boolean) : PermissionResult()
}