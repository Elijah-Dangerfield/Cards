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

    if (configured == EXPECTED_HOOKS_PATH) return

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
