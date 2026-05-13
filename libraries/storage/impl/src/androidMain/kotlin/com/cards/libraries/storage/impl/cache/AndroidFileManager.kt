package com.dangerfield.cards.libraries.storage.impl.cache

import android.content.Context
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.storage.FileManager
import me.tatarka.inject.annotations.Inject
import okio.Path
import okio.Path.Companion.toPath
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidFileManager @Inject constructor(
    private val context: Context
) : FileManager {
    override fun createFile(name: String): Path {
        return context.filesDir.resolve(name).absolutePath.toPath()
    }

    override fun deleteFile(name: String) {
        Catching {
            context.filesDir.resolve(name).deleteRecursively()
        }
            .logOnFailure("Failed to delete file $name")
    }

    override fun deleteAll() {
        Catching {
            context.filesDir.listFiles()?.forEach { file ->
                file.deleteRecursively()
            }
        }
            .onSuccess { KLog.i("Deleted all cache") }
            .logOnFailure()
    }
}
