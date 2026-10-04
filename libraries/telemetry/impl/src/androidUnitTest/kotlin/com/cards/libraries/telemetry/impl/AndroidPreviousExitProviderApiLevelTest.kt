package com.dangerfield.cards.libraries.telemetry.impl

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards CARDS-CK: `AndroidPreviousExitProvider` must not name an API-30 type
 * in any of its own signatures.
 *
 * The original bug was `private val exitInfo: ApplicationExitInfo? by lazy`.
 * The `Build.VERSION.SDK_INT` guard sat *inside* the method it called, but ART
 * has to resolve a member's signature to execute it at all, so the synthetic
 * `getExitInfo()` threw `NoClassDefFoundError` on Android 10 before the guard
 * could run. Every device below API 30 stopped emitting `app.launched`.
 *
 * **This cannot be caught by an ordinary unit test.** The JVM resolves
 * `android.app.ApplicationExitInfo` happily from the android.jar stubs on the
 * test classpath at any API level, so there is no `NoClassDefFoundError` to
 * assert on — [PreviousExitReasonMappingTest] uses the class directly and
 * always passed, before and after the bug. What is checkable is the structural
 * rule the fix encodes, so that is what this asserts: read the compiled class
 * and fail if the platform type appears in a field type, return type, or
 * parameter type. That reads as indirect, and it is; it is also the only level
 * at which the real defect is visible without an API-29 device or emulator.
 */
class AndroidPreviousExitProviderApiLevelTest {

    @Test
    fun providerNeverNamesAnApi30TypeInItsOwnSignatures() {
        val offenders = api30TypeUsagesIn(AndroidPreviousExitProvider::class.java)

        assertTrue(
            offenders.isEmpty(),
            "AndroidPreviousExitProvider exposes API-30 types in its own signatures, which ART " +
                "must resolve before any SDK_INT guard can run — this is CARDS-CK:\n" +
                offenders.joinToString("\n") { "  - $it" } +
                "\nKeep the platform type inside the @RequiresApi reader and hand back ExitRecord.",
        )
    }

    @Test
    fun theRecordItHoldsIsPlainData() {
        val offenders = api30TypeUsagesIn(ExitRecord::class.java)

        assertTrue(
            offenders.isEmpty(),
            "ExitRecord is the type the provider holds, so it is reachable below API 30 too:\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    private fun api30TypeUsagesIn(cls: Class<*>): List<String> = buildList {
        cls.declaredFields.forEach { field ->
            if (field.type.name in API_30_TYPES) {
                add("field `${field.name}` is ${field.type.name}")
            }
        }
        cls.declaredMethods.forEach { method ->
            if (method.returnType.name in API_30_TYPES) {
                add("method `${method.name}` returns ${method.returnType.name}")
            }
            method.parameterTypes.forEach { param ->
                if (param.name in API_30_TYPES) {
                    add("method `${method.name}` takes ${param.name}")
                }
            }
        }
        cls.declaredConstructors.forEach { ctor ->
            ctor.parameterTypes.forEach { param ->
                if (param.name in API_30_TYPES) {
                    add("constructor takes ${param.name}")
                }
            }
        }
    }

    private companion object {
        // minSdk is 24. ApplicationExitInfo landed in API 30 (R).
        val API_30_TYPES = setOf("android.app.ApplicationExitInfo")
    }
}
