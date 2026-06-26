#!/usr/bin/env kotlin

/**
 * Rotate the server admin API token (`ADMIN_API_TOKEN`) for an environment.
 *
 * A single admin token lives in four places that must stay in lockstep, which is
 * exactly the kind of thing that drifts when done by hand:
 *   1. the Fly app secret           (`ADMIN_API_TOKEN` on cards-server-<env>)
 *   2. the GitHub Actions secret     (`CARDS_ADMIN_API_TOKEN_<ENV>`)
 *   3. the local admin tool file      (`apps/admin/admin-tokens.local.properties`)
 *   4. (implicitly) the baked admin bundle — rebuild it after rotating
 *
 * This generates a fresh token and updates 1–3 atomically per environment.
 *
 * Usage:
 *   scripts/rotate_admin_tokens.main.kts dev
 *   scripts/rotate_admin_tokens.main.kts prod
 *   scripts/rotate_admin_tokens.main.kts all
 *
 * Requires `fly` (authed) and `gh` (authed, `repo` scope). Rotating restarts the
 * Fly app and immediately invalidates the old token, so each env asks to confirm.
 */

import java.io.File
import java.security.SecureRandom

data class EnvConfig(val key: String, val flyApp: String, val ghSecret: String)

val environments = mapOf(
    "dev" to EnvConfig(key = "dev", flyApp = "cards-server-dev", ghSecret = "CARDS_ADMIN_API_TOKEN_DEV"),
    "prod" to EnvConfig(key = "prod", flyApp = "cards-server-prod", ghSecret = "CARDS_ADMIN_API_TOKEN_PROD"),
)

fun usageAndExit(): Nothing {
    println("Usage: scripts/rotate_admin_tokens.main.kts <dev|prod|all>")
    kotlin.system.exitProcess(1)
}

fun prompt(label: String): String {
    print("$label: ")
    return readlnOrNull()?.trim().orEmpty()
}

/** Run a command, optionally feeding [stdin]. Returns exit code + combined output. */
fun run(cmd: List<String>, stdin: String? = null): Pair<Int, String> {
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    if (stdin != null) process.outputStream.use { it.write(stdin.toByteArray()) }
    val output = process.inputStream.bufferedReader().readText()
    return process.waitFor() to output
}

/** 32 random bytes as lowercase hex — same shape as the existing tokens. */
fun newToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

fun repoRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").exists()) return dir
        dir = dir.parentFile
    }
    error("Could not find repo root (settings.gradle.kts) from ${File("").absolutePath}")
}

/** Replace (or append) `key=value` in the local properties file, preserving the rest. */
fun updateLocalToken(file: File, key: String, value: String) {
    val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
    val index = lines.indexOfFirst { it.trimStart().startsWith("$key=") }
    val line = "$key=$value"
    if (index >= 0) lines[index] = line else lines.add(line)
    file.writeText(lines.joinToString("\n") + "\n")
}

fun rotate(env: EnvConfig, localFile: File) {
    println("\n=== Rotating ADMIN_API_TOKEN for ${env.key} (${env.flyApp}) ===")
    val confirm = prompt("This restarts ${env.flyApp} and invalidates the current token. Type '${env.key}' to proceed")
    if (confirm != env.key) {
        println("Skipped ${env.key}.")
        return
    }

    val token = newToken()

    // 1. Fly secret — `import` reads KEY=VALUE from stdin so the token never lands
    //    in the process arg list. This triggers a rolling restart of the app.
    println("→ Setting Fly secret on ${env.flyApp} (restarts the app)…")
    val (flyCode, flyOut) = run(listOf("fly", "secrets", "import", "-a", env.flyApp), stdin = "ADMIN_API_TOKEN=$token\n")
    if (flyCode != 0) {
        println(flyOut)
        error("fly secrets import failed for ${env.flyApp} — aborting before the other stores diverge.")
    }

    // 2. GitHub Actions secret — value piped via stdin (kept out of argv).
    println("→ Setting GitHub secret ${env.ghSecret}…")
    val (ghCode, ghOut) = run(listOf("gh", "secret", "set", env.ghSecret), stdin = token)
    if (ghCode != 0) {
        println(ghOut)
        error("gh secret set failed for ${env.ghSecret}. Fly is already rotated — re-run for ${env.key} to resync.")
    }

    // 3. Local admin tool file.
    println("→ Updating apps/admin/admin-tokens.local.properties (${env.key}=…)")
    updateLocalToken(localFile, env.key, token)

    println("\n  ${env.key} rotated. New token (copy if needed):")
    println("  $token")
}

// ── main ─────────────────────────────────────────────────────────────────────

if (args.isEmpty()) usageAndExit()
val requested = when {
    args.contains("all") -> listOf("dev", "prod")
    else -> args.toList()
}
val targets = requested.map { name ->
    environments[name] ?: run { println("Unknown environment '$name'."); usageAndExit() }
}

val root = repoRoot()
val localFile = File(root, "apps/admin/admin-tokens.local.properties")

targets.forEach { rotate(it, localFile) }

println(
    """

    Done. Next steps:
    - Rebuild the admin tool so the new token(s) get baked into the bundle:
        ./gradlew :apps:admin:jsBrowserDevelopmentRun --continuous
    - The Fly apps restart on secret change; give them a few seconds before reconnecting.
    - The GitHub secrets now match, so the deploy-time manifest upload keeps working.
    """.trimIndent(),
)
