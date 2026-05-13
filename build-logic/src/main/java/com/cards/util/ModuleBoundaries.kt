package com.dangerfield.cards.util

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Enforces module-dependency rules so the graph stays cycle-free and the
 * api/impl split holds.
 *
 * Rules enforced (see AGENTS.md → Module Structure):
 *   1. Only :apps:* modules may depend on any `*:impl` module. impls are DI
 *      wiring and are composed by the app, not consumed by other features or
 *      libraries.
 *   2. Feature api modules (:features:<x>, no :impl suffix) may not depend on
 *      another feature's api. api-to-api across features becomes a cycle the
 *      moment someone adds the reverse edge. Shared types belong in a library.
 *      Sub-modules of the same feature (e.g. :features:timers:storage depending
 *      on :features:timers) are allowed.
 *
 * The check runs during configuration and skips :apps:* (the app is the glue).
 */
private const val REGISTERED_MARKER = "com.dangerfield.cards.moduleBoundaries.registered"

fun Project.enforceModuleBoundaries() {
    if (path.startsWith(":apps:")) return
    val extra = extensions.extraProperties
    if (extra.has(REGISTERED_MARKER)) return
    extra.set(REGISTERED_MARKER, true)

    afterEvaluate {
        configurations.forEach { config ->
            config.dependencies.toList().forEach { dep ->
                if (dep is ProjectDependency) {
                    checkImplDependency(path, dep.path)
                    checkFeatureApiToApi(path, dep.path)
                }
            }
        }
    }
}

private fun Project.checkImplDependency(self: String, dep: String) {
    if (!dep.endsWith(":impl")) return
    val api = dep.removeSuffix(":impl")
    throw GradleException(
        """

        Module boundary violation: $self → $dep

        Only :apps:* modules may depend on impl modules. Depend on the api
        module instead:

          implementation(project("$api"))

        See AGENTS.md → Module Structure.

        """.trimIndent()
    )
}

private fun Project.checkFeatureApiToApi(self: String, dep: String) {
    if (!self.startsWith(":features:")) return
    if (!dep.startsWith(":features:")) return
    if (self.endsWith(":impl")) return
    if (dep.endsWith(":impl")) return
    val selfRoot = featureRoot(self)
    val depRoot = featureRoot(dep)
    if (selfRoot == depRoot) return
    throw GradleException(
        """

        Module boundary violation: $self → $dep

        Feature api modules must not depend on other feature apis (api-to-api
        is a cycle risk the moment someone adds the reverse edge). Either:
          - Move the shared types into a library, or
          - Depend from an impl module (feature-impl → feature-api is allowed).

        See AGENTS.md → Module Structure.

        """.trimIndent()
    )
}

private fun featureRoot(path: String): String {
    val parts = path.split(":").filter { it.isNotEmpty() }
    return if (parts.size >= 2) ":${parts[0]}:${parts[1]}" else path
}
