package com.dangerfield.cards.libraries.config

/**
 * Represents a locally persisted override for a fully qualified config [path].
 * These are created when setting a value in the QA menu.
 */
class ConfigOverride<T : Any>(
    val path: String,
    val value: T
)
