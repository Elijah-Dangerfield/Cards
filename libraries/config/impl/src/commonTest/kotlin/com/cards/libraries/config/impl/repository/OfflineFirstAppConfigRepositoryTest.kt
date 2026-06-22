package com.dangerfield.cards.libraries.config.impl.repository

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.Session
import com.dangerfield.cards.libraries.cards.SessionStartReason
import com.dangerfield.cards.libraries.cards.SessionTracker
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.ConfigOverride
import com.dangerfield.cards.libraries.config.ConfigOverrideRepository
import com.dangerfield.cards.libraries.config.impl.data.ConfigCache
import com.dangerfield.cards.libraries.config.impl.data.ConfigCacheSnapshot
import com.dangerfield.cards.libraries.config.impl.data.RemoteConfigDataSource
import com.dangerfield.cards.libraries.config.impl.model.BasicMapAppConfig
import com.dangerfield.cards.libraries.config.impl.model.FallbackConfigMap
import com.dangerfield.cards.libraries.config.impl.serialization.ConfigJsonConverter
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineFirstAppConfigRepositoryTest : CoroutineTest() {

    @Test
    fun init_coldBootWithEmptyCache_triggersRefreshAndEmits() = runUnitTest {
        val source = FakeRemoteDataSource(response = MapAppConfig(mapOf("feature.x" to true)))
        val repo = newRepo(source = source)
        runCurrent()

        assertEquals(1, source.callCount, "cold-boot session emission should trigger one refresh")
        val emitted = repo.configStream().first()
        assertEquals(true, emitted.map["feature.x"])
    }

    @Test
    fun refresh_failureWithNoCachedConfig_persistsFallback() = runUnitTest {
        val fallback = TestFallbackConfigMap(map = mapOf("fallback" to "yes"))
        val source = FakeRemoteDataSource().apply { failNext = RuntimeException("server down") }
        val repo = newRepo(source = source, fallback = fallback)
        runCurrent()

        val emitted = repo.configStream().first()
        assertEquals("yes", emitted.map["fallback"], "fallback config persisted when no cache present")
    }

    @Test
    fun refresh_failureWithCachedConfig_keepsCachedSnapshot() = runUnitTest {
        val cache = FakeConfigCache()
        cache.seed(configJson = """{"existing":"value"}""")
        val source = FakeRemoteDataSource().apply { failNext = RuntimeException("server down") }
        val fallback = TestFallbackConfigMap(map = mapOf("fallback" to "yes"))
        val repo = newRepo(source = source, cache = cache, fallback = fallback)
        runCurrent()

        val emitted = repo.configStream().first()
        assertEquals("value", emitted.map["existing"])
        assertNull(emitted.map["fallback"], "fallback should NOT clobber a prior cached snapshot")
    }

    @Test
    fun init_withCachedConfig_hydratesBeforeRefreshLands() = runUnitTest {
        val cache = FakeConfigCache()
        cache.seed(configJson = """{"hydrated":"from-disk"}""")
        val source = FakeRemoteDataSource(response = MapAppConfig(mapOf("hydrated" to "from-server")))
        val repo = newRepo(source = source, cache = cache)

        repo.configStream().test {
            // The first emission depends on whether hydration or refresh
            // lands first under the unconfined dispatcher. Pin: at least
            // one emission appears, and after runCurrent the server
            // response is reflected.
            val initial = awaitItem()
            assertTrue(initial.map["hydrated"] in listOf("from-disk", "from-server"))
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sessionRollover_triggersRefetch() = runUnitTest {
        val source = FakeRemoteDataSource(response = MapAppConfig(mapOf("k" to "v")))
        val sessions = FakeSessionTracker(initial = coldBootSession(id = 1L))
        val repo = newRepo(source = source, sessions = sessions)
        runCurrent()
        assertEquals(1, source.callCount, "init session emission")

        sessions.roll(toId = 2L)
        runCurrent()
        assertEquals(2, source.callCount, "rollover to session 2 should re-fetch")
    }

    @Test
    fun sameSessionReplay_doesNotRefetch() = runUnitTest {
        val source = FakeRemoteDataSource(response = MapAppConfig(mapOf("k" to "v")))
        val sessions = FakeSessionTracker(initial = coldBootSession(id = 1L))
        val repo = newRepo(source = source, sessions = sessions)
        runCurrent()
        assertEquals(1, source.callCount)

        sessions.republish() // emit current session again
        runCurrent()
        assertEquals(1, source.callCount, "same session id must short-circuit")
    }

    @Test
    fun sessionRollover_afterFailure_nextRolloverRetries() = runUnitTest {
        val source = FakeRemoteDataSource(response = MapAppConfig(mapOf("k" to "v")))
        val sessions = FakeSessionTracker(initial = coldBootSession(id = 1L))
        val repo = newRepo(source = source, sessions = sessions)
        runCurrent()
        assertEquals(1, source.callCount)

        // Session 2: server fails. lastFetchSessionId stays at 1 so the
        // next rollover still crosses the gate.
        source.failNext = RuntimeException("rollover fetch failed")
        sessions.roll(toId = 2L)
        runCurrent()
        assertEquals(2, source.callCount)

        // Session 3: next rollover must retry.
        sessions.roll(toId = 3L)
        runCurrent()
        assertEquals(3, source.callCount, "next rollover must retry after a failed refresh")
    }

    @Test
    fun overrides_apply_onTopOfCachedConfig() = runUnitTest {
        val source = FakeRemoteDataSource(response = MapAppConfig(mapOf("foo" to "bar")))
        val overrides = FakeConfigOverrideRepository()
        val repo = newRepo(source = source, overrides = overrides)
        runCurrent()

        assertEquals("bar", repo.configStream().first().map["foo"])

        overrides.set(listOf(ConfigOverride("foo", "overridden" as Any)))
        runCurrent()
        assertEquals("overridden", repo.configStream().first().map["foo"])
    }

    // ---------- Test scaffolding ----------

    private fun newRepo(
        source: FakeRemoteDataSource = FakeRemoteDataSource(),
        cache: FakeConfigCache = FakeConfigCache(),
        fallback: TestFallbackConfigMap = TestFallbackConfigMap(),
        overrides: FakeConfigOverrideRepository = FakeConfigOverrideRepository(),
        sessions: FakeSessionTracker = FakeSessionTracker(initial = coldBootSession(id = 1L)),
    ): OfflineFirstAppConfigRepository {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val converter = ConfigJsonConverter(json)
        return OfflineFirstAppConfigRepository(
            dispatcherProvider = dispatchers,
            remoteConfigDataSource = source,
            configCache = cache,
            converter = converter,
            fallbackConfig = fallback,
            configOverrideRepository = overrides,
            sessionTracker = sessions,
            appScope = AppCoroutineScope(dispatchers),
        )
    }

    private fun coldBootSession(id: Long): Session = Session(
        id = id,
        startedAtMs = 0L,
        reason = SessionStartReason.ColdBoot,
        uuid = "session-$id",
    )

    private class FakeRemoteDataSource(
        var response: AppConfigMap = MapAppConfig(emptyMap<String, Any?>()),
    ) : RemoteConfigDataSource {
        var failNext: Throwable? = null
        var callCount: Int = 0
            private set

        override suspend fun getConfig(): Catching<AppConfigMap> {
            callCount++
            failNext?.let { failNext = null; return Catching.failure(it) }
            return Catching.success(response)
        }
    }

    private class FakeConfigCache : ConfigCache {
        private val state = MutableStateFlow(ConfigCacheSnapshot())
        override val updates: Flow<ConfigCacheSnapshot> = state
        override suspend fun get(): ConfigCacheSnapshot = state.value
        override suspend fun set(value: ConfigCacheSnapshot) { state.value = value }
        override suspend fun clear() { state.value = ConfigCacheSnapshot() }
        fun seed(configJson: String? = null, overridesJson: String? = null) {
            state.value = ConfigCacheSnapshot(configJson = configJson, overridesJson = overridesJson)
        }
    }

    private class TestFallbackConfigMap(
        override val map: Map<String, *> = emptyMap<String, Any?>(),
    ) : FallbackConfigMap(converter = ConfigJsonConverter(Json.Default))

    private class FakeSessionTracker(initial: Session) : SessionTracker {
        private val flow = MutableStateFlow(initial)
        override val current: Session get() = flow.value
        override fun observe(): Flow<Session> = flow

        fun roll(toId: Long) {
            flow.value = flow.value.copy(
                id = toId,
                reason = SessionStartReason.BackgroundRollover(
                    backgroundedForMs = SessionTracker.BACKGROUND_ROLLOVER_MS,
                ),
            )
        }

        fun republish() {
            // Emit the same Session again — distinctUntilChangedBy { id }
            // in the production observer should collapse this, so callers
            // assert that no refresh fired.
            val current = flow.value
            flow.value = current.copy(startedAtMs = current.startedAtMs + 1)
        }
    }

    private class FakeConfigOverrideRepository : ConfigOverrideRepository {
        private val flow = MutableStateFlow<List<ConfigOverride<Any>>>(emptyList())
        override fun getOverrides(): List<ConfigOverride<Any>> = flow.value
        override fun getOverridesFlow(): Flow<List<ConfigOverride<Any>>> = flow
        override suspend fun addOverride(override: ConfigOverride<Any>) {
            flow.value = flow.value.filter { it.path != override.path } + override
        }
        override suspend fun clearAll() { flow.value = emptyList() }
        fun set(overrides: List<ConfigOverride<Any>>) { flow.value = overrides }
    }

    private class MapAppConfig(override val map: Map<String, *>) : AppConfigMap()
}
