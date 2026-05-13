package com.dangerfield.cards.libraries.navigation.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.navigation.WebLinkLauncher
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidWebLinkLauncher @Inject constructor(
    private val context: Context,
) : WebLinkLauncher {

    override fun open(url: String): Catching<Unit> = Catching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
