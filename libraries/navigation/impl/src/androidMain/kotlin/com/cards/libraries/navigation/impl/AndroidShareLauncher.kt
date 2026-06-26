package com.dangerfield.cards.libraries.navigation.impl

import android.content.Context
import android.content.Intent
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.navigation.ShareLauncher
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidShareLauncher @Inject constructor(
    private val context: Context,
) : ShareLauncher {

    override fun share(text: String): Catching<Unit> = Catching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
