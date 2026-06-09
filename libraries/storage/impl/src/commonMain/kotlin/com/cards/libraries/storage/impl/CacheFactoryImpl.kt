package com.dangerfield.cards.libraries.storage.impl.cache

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.storage.Cache
import com.dangerfield.cards.libraries.storage.CacheFactory
import com.dangerfield.cards.libraries.storage.CacheJsonSerializer
import com.dangerfield.cards.libraries.storage.FileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.tatarka.inject.annotations.Inject
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.SYSTEM
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, CacheFactory::class)
@Inject
class DataStoreCacheFactory (
    private val scope: AppCoroutineScope,
    private val fileManager: FileManager,
) : CacheFactory {

    private val inMemoryCaches: MutableSet<InMemoryCache<*>> = mutableSetOf()
    private val persistentCaches: MutableMap<String, Cache<*>> = mutableMapOf()

    override fun <T : Any> inMemory(
        defaultValue: () -> T,
    ): Cache<T> = InMemoryCache(
        defaultValue,
    ).also { inMemoryCaches.add(it) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> persistent(
        name: String,
        serializer: CacheJsonSerializer<T>,
        loadEagerly: Boolean
        ): Cache<T> {
        return persistentCaches.getOrPut(name) {
            createPersistentCache(name, serializer, loadEagerly)
        } as Cache<T>
    }
    
    private fun <T : Any> createPersistentCache(
        name: String,
        serializer: CacheJsonSerializer<T>,
        loadEagerly: Boolean
    ): Cache<T> {
        val storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = serializer.toOkioSerializer(),
            producePath = { fileManager.createFile("$name.json") },
        )

        val corruptionHandler = ReplaceFileCorruptionHandler<T> { ex ->
            KLog.e("File Corrupted", ex)
            runBlocking { serializer.read(null) }
        }

        val dataStore: DataStore<T> = DataStoreFactory.create(
            storage = storage,
            scope = scope,
            corruptionHandler = corruptionHandler
        )

        if (loadEagerly) {
            scope.launch {
                dataStore.data.first()
            }
        }

        return DataStoreCache(
            dataStore,
            deleteFile = { fileManager.deleteFile(name) }
        )
    }

    private fun <T : Any> CacheJsonSerializer<T>.toOkioSerializer(): OkioSerializer<T> =
        object : OkioSerializer<T> {
            override val defaultValue: T
                get() = runBlocking { read(null) }

            override suspend fun readFrom(source: BufferedSource): T =
                read(source.readByteArray())

            override suspend fun writeTo(t: T, sink: BufferedSink) {
                val bytes = write(t)
                sink.write(bytes)
            }
        }
}

private class DataStoreCache<T : Any>(
    private val dataStore: DataStore<T>,
    private val deleteFile: () -> Unit,
) : Cache<T> {

    override val updates: Flow<T> = dataStore.data

    override suspend fun get(): T = dataStore.data.first()

    override suspend fun set(value: T) {
        dataStore.updateData { value }
    }

    /**
     * Atomic read-modify-write. Overrides the default get-then-set (which can
     * clobber a concurrent update of a *different* field on the same blob) by
     * running the transform inside DataStore's `updateData`, which serializes
     * transforms and hands each the latest value. Load-bearing now that
     * sign-out fires several independent `appCache.update {}` calls at once
     * (e.g. clearing `hasUserOnboarded` while a listener resets account-scoped
     * fields).
     */
    override suspend fun update(transform: (T) -> T): T {
        lateinit var result: T
        dataStore.updateData { current -> transform(current).also { result = it } }
        return result
    }

    override suspend fun clear() { deleteFile() }
}
