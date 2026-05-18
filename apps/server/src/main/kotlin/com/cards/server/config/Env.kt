package com.dangerfield.cards.server.config

import java.io.File

/**
 * Single source of truth for environment-variable lookup.
 *
 * Resolution order (first hit wins):
 *  1. Real OS environment (`System.getenv`). This is what Fly.io populates
 *     from `fly secrets set …` at runtime.
 *  2. The local `.env` file (gitignored, dev-only). Loaded lazily on first
 *     read so production runs never touch the filesystem.
 *
 * Keep this class boring on purpose — config loaders that grow features
 * (interpolation, profiles, includes) become their own debugging surface.
 * Here we want: "is this var set, what is its value, end of file."
 */
class Env(
    private val envFilePath: String = DEFAULT_ENV_PATH,
    private val realEnv: (String) -> String? = System::getenv,
) {

    private val fileBacking: Map<String, String> by lazy {
        loadEnvFile(File(envFilePath))
    }

    /**
     * Returns the value for [key] from the OS environment, falling back to
     * the .env file if not set. Returns null if neither source has it.
     */
    operator fun get(key: String): String? =
        realEnv(key)?.takeIf { it.isNotBlank() } ?: fileBacking[key]?.takeIf { it.isNotBlank() }

    /** Returns the value or throws — for fields the server cannot boot without. */
    fun require(key: String): String =
        get(key) ?: error("Required env var '$key' is not set (checked OS env and $envFilePath)")

    fun int(key: String, default: Int): Int = get(key)?.toIntOrNull() ?: default
    fun long(key: String, default: Long): Long = get(key)?.toLongOrNull() ?: default

    companion object {
        const val DEFAULT_ENV_PATH = "apps/server/.env"

        /**
         * Minimal .env parser. Supports `KEY=value` and `KEY="quoted value"`,
         * skips blank lines and `# comments`. Does NOT do shell interpolation,
         * variable references, or multiline values — paste the literal value
         * you want, URL-encoded if it's a connection string.
         */
        private fun loadEnvFile(file: File): Map<String, String> {
            if (!file.exists()) return emptyMap()
            return buildMap {
                file.readLines().forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val eq = line.indexOf('=')
                    if (eq <= 0) return@forEach
                    val key = line.substring(0, eq).trim()
                    val rawValue = line.substring(eq + 1).trim()
                    val value = when {
                        rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length >= 2 ->
                            rawValue.substring(1, rawValue.length - 1)
                        rawValue.startsWith("'") && rawValue.endsWith("'") && rawValue.length >= 2 ->
                            rawValue.substring(1, rawValue.length - 1)
                        else -> rawValue
                    }
                    put(key, value)
                }
            }
        }
    }
}
