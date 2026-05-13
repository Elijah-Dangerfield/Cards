@file:OptIn(ExperimentalObjCName::class)

package com.dangerfield.cards.libraries.cards

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

@ObjCName("PermissionManager", exact = true)
interface PermissionManager {
    suspend fun ensurePermission(permission: Permission): PermissionResult
    suspend fun requestPermission(permission: Permission): PermissionResult
    fun checkPermissionStatus(permission: Permission): PermissionStatus
    fun openAppSettings()
}
