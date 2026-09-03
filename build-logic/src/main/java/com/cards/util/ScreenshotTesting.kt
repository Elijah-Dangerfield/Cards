package com.dangerfield.cards.util

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

/**
 * Configures the unit-test JVM for rendering work.
 *
 * Gradle gives a test worker **512MB** by default, which is fine for logic tests
 * and nowhere near enough for screenshot tests: one full-screen golden at
 * 411dp x 891dp on xhdpi is a 822 x 1782 ARGB bitmap, roughly 5.9MB of heap
 * before any Compose or Robolectric overhead, and a module can have a hundred
 * previews.
 *
 * Running out of heap here does not fail loudly. The worker drops into a GC
 * death spiral and the build sits at 0% CPU producing nothing, which reads as a
 * deadlock. It cost an afternoon to recognise, hence the comment.
 *
 * `forkEvery` caps how many test classes a single worker handles before it is
 * replaced, so leaked bitmaps and Robolectric's per-configuration class loaders
 * can't accumulate across a whole module's suite.
 */
internal fun Project.configureUnitTestJvm() {
    tasks.withType<Test>().configureEach {
        maxHeapSize = "4g"
        forkEvery = 40
        // Robolectric's own scratch space. Left on the default temp dir it can
        // collide between parallel workers.
        systemProperty("robolectric.logging", "stdout")
    }
}
