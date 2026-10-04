package com.dangerfield.cards.util

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

private const val EXPECTED_HOOKS_PATH = ".githooks"

fun Project.verifyGitHooksInstalled() {
    if (System.getenv("CI") != null) return
    if (System.getProperty("cards.skipGitHooksCheck") == "true") return

    val gitDir = resolveGitDir(rootProject.projectDir) ?: return
    val configFile = File(gitDir, "config").takeIf { it.exists() } ?: return
    val configured = readHooksPath(configFile)

    if (pointsAtOurHooks(configured, rootProject.projectDir)) return

    throw GradleException(
        """

        Git hooks are not installed. Conventional Commits enforcement is missing.

          Run: ./scripts/install_hooks.sh

        This wires $EXPECTED_HOOKS_PATH/commit-msg as a local hook so release-please can
        derive version bumps from commit history. See docs/release-automation.md.

        To bypass (e.g. non-interactive build outside CI), pass
        -Dcards.skipGitHooksCheck=true or set the CI env var.

        """.trimIndent()
    )
}

/**
 * Whether [configured] names this repo's hooks directory, however it is spelled.
 *
 * Deliberately not `configured == ".githooks"`. A git worktree has no config of
 * its own, so anything that runs `git config core.hooksPath` from inside one
 * writes an **absolute** path into the main checkout's config. The hooks still
 * work; only a string comparison notices. It surfaces as a Gradle failure at
 * plugin application and, through `pre-push`, as a message about detekt findings
 * that do not exist — which sends you reading your own code instead of your git
 * config. It cost time repeatedly in this repo, most recently while removing the
 * agent worktrees during the 2026-10-04 develop retirement.
 *
 * Git resolves a relative `core.hooksPath` against the top of the working tree,
 * so that is what [projectDir] is for. Comparing canonical paths accepts both
 * spellings and still rejects a hooks directory that is genuinely elsewhere.
 *
 * Ported from KMPTemplate `1ffa2cf`, which fixed this on 2026-09-28.
 */
private fun pointsAtOurHooks(configured: String?, projectDir: File): Boolean {
    if (configured.isNullOrBlank()) return false
    val expected = File(projectDir, EXPECTED_HOOKS_PATH)
    val actual = File(configured).let { if (it.isAbsolute) it else File(projectDir, configured) }
    return runCatching { actual.canonicalFile == expected.canonicalFile }.getOrDefault(false)
}

private fun resolveGitDir(projectDir: File): File? {
    val gitPath = File(projectDir, ".git")
    if (!gitPath.exists()) return null
    if (gitPath.isDirectory) return gitPath
    // Worktree / submodule: .git is a file containing `gitdir: <path>`.
    val pointer = gitPath.readText().trim().removePrefix("gitdir:").trim()
    val resolved = if (File(pointer).isAbsolute) File(pointer) else File(projectDir, pointer)
    return resolved.takeIf { it.exists() }
}

private fun readHooksPath(config: File): String? {
    var inCore = false
    for (raw in config.readLines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
        if (line.startsWith("[")) {
            inCore = line.substringBefore(']').trim('[').trim().equals("core", ignoreCase = true)
            continue
        }
        if (!inCore) continue
        val match = Regex("""(?i)^hooksPath\s*=\s*(.+)$""").find(line) ?: continue
        return match.groupValues[1].trim().trim('"')
    }
    return null
}
