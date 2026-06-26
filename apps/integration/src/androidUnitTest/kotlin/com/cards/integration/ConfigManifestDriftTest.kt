package com.cards.integration

import com.dangerfield.cards.features.upgrade.MaintenanceMessage
import com.dangerfield.cards.features.upgrade.MaintenanceMode
import com.dangerfield.cards.features.upgrade.MinSupportedVersionCode
import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.ConfiguredValue
import com.dangerfield.cards.libraries.config.DoubleConfigValue
import com.dangerfield.cards.libraries.config.FlagConfigValue
import com.dangerfield.cards.libraries.config.IntConfigValue
import com.dangerfield.cards.libraries.config.LongConfigValue
import com.dangerfield.cards.libraries.config.StringConfigValue
import com.dangerfield.cards.libraries.identity.AppleSignInEnabled
import com.dangerfield.cards.libraries.identity.GoogleSignInEnabled
import com.dangerfield.cards.libraries.identity.OnboardingStarterGrant
import com.dangerfield.cards.libraries.identity.OnboardingSuggestedName
import com.dangerfield.cards.libraries.social.SocialEnabled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Self-healing guard for the config manifest. The admin tool's per-version
 * "what shipped" view is fed by `apps/admin/config-manifest-registry.json`, a
 * maintained list (the live `Set<ConfiguredValue<*>>` multibinding is Android/
 * iOS-only and can't be read from the JS admin module or a JVM build task).
 *
 * This test instantiates the REAL scalar [ConfiguredValue] classes and fails if
 * the registry has drifted from their in-code path / type / default / allowed
 * values — so changing a default in code without updating the registry turns the
 * build red instead of silently shipping a stale "what 1.0.1 shipped with".
 */
class ConfigManifestDriftTest {

    private val stubConfig = object : AppConfigMap() {
        override val map: Map<String, *> = emptyMap<String, Any?>()
    }

    /** The scalar values the registry is supposed to mirror, one per registry row. */
    private val codeValues: List<ConfiguredValue<*>> = listOf(
        SocialEnabled(stubConfig),
        GoogleSignInEnabled(stubConfig),
        AppleSignInEnabled(stubConfig),
        MinSupportedVersionCode(stubConfig),
        MaintenanceMode(stubConfig),
        MaintenanceMessage(stubConfig),
        OnboardingStarterGrant(stubConfig),
        OnboardingSuggestedName(stubConfig),
    )

    @Test
    fun registryMatchesInCodeDefaults() {
        val registry = loadRegistry()
        val codeByPath = codeValues.associate { it.path to it.snapshot() }

        val problems = buildList {
            codeByPath.forEach { (path, code) ->
                val reg = registry[path]
                when {
                    reg == null -> add("$path is in code but missing from config-manifest-registry.json")
                    reg.type != code.type -> add("$path: type is '${reg.type}' in registry, '${code.type}' in code")
                    reg.default != code.default -> add("$path: default is ${reg.default} in registry, ${code.default} in code")
                    reg.allowedValues != code.allowedValues ->
                        add("$path: allowedValues are ${reg.allowedValues} in registry, ${code.allowedValues} in code")
                }
            }
            registry.keys.filter { it !in codeByPath }.forEach {
                add("$it is in the registry but has no matching scalar ConfiguredValue (stale entry?)")
            }
        }

        assertTrue(
            problems.isEmpty(),
            "apps/admin/config-manifest-registry.json drifted from the in-code config defaults:\n  " +
                problems.joinToString("\n  ") +
                "\n\nUpdate apps/admin/config-manifest-registry.json to match.",
        )
    }

    /** Normalized (type, default-as-string, allowed-as-strings) for comparison. */
    private data class Snapshot(val type: String, val default: String, val allowedValues: List<String>?)

    private fun ConfiguredValue<*>.snapshot(): Snapshot {
        val type = when (this) {
            is FlagConfigValue -> "boolean"
            is IntConfigValue -> "int"
            is LongConfigValue -> "long"
            is DoubleConfigValue -> "double"
            is StringConfigValue -> "string"
            else -> "json"
        }
        return Snapshot(type, default.toString(), allowedValues?.map { it.toString() })
    }

    private fun loadRegistry(): Map<String, Snapshot> {
        val file = registryFile()
        val array = Json.parseToJsonElement(file.readText()) as JsonArray
        return array.associate { element ->
            val obj = element.jsonObject
            val path = obj.string("path")
            path to Snapshot(
                type = obj.string("type"),
                default = (obj["default"] as JsonPrimitive).content,
                allowedValues = (obj["allowedValues"] as? JsonArray)?.map { (it as JsonPrimitive).content },
            )
        }
    }

    private fun JsonObject.string(key: String): String = (this[key] as JsonPrimitive).content

    /** Walk up from the working dir to the repo root, then the committed registry. */
    private fun registryFile(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) {
                return File(dir, "apps/admin/config-manifest-registry.json")
            }
            dir = dir.parentFile
        }
        error("Could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
    }
}
