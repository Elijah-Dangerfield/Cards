package com.dangerfield.cards

import com.dangerfield.cards.libraries.cards.PermissionManager
import com.dangerfield.cards.libraries.identity.auth.AppleSignInCoordinator
import com.dangerfield.cards.libraries.review.ReviewLauncher
import com.dangerfield.cards.libraries.ui.nativeviews.NativeViewFactory
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@MergeComponent(AppScope::class)
@SingleIn(AppScope::class)
abstract class IosAppComponent(
    private val permissionManager: PermissionManager,
    private val reviewLauncher: ReviewLauncher,
    // The Swift `IOSAppleSignInCoordinator` (ASAuthorizationController flow),
    // passed in from `iOSApp.swift`. Android binds its own no-op via anvil.
    private val appleSignInCoordinator: AppleSignInCoordinator,
    val nativeViewFactory: NativeViewFactory
) : AppComponent {

    @Provides
    fun providePermissionManager(): PermissionManager = permissionManager

    @Provides
    fun provideReviewLauncher(): ReviewLauncher = reviewLauncher

    @Provides
    fun provideAppleSignInCoordinator(): AppleSignInCoordinator = appleSignInCoordinator
}


@MergeComponent.CreateComponent
expect fun create(
    permissionManager: PermissionManager,
    reviewLauncher: ReviewLauncher,
    appleSignInCoordinator: AppleSignInCoordinator,
    nativeViewFactory: NativeViewFactory
): IosAppComponent
