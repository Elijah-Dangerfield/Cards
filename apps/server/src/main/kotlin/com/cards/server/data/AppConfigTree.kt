package com.dangerfield.cards.server.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Assembles the sparse, nested config tree the client expects from a flat
 * `path -> value` map.
 *
 * The client fetches an override tree keyed by [com.dangerfield.cards.libraries.config.ConfiguredValue.path]
 * segments — `{"upgrade": {"minSupportedVersionCode": 1}, "social": {"enabled": false}}` —
 * but the server stores values flat, one row per dotted path. This expands the
 * dots back into nested [JsonObject]s.
 *
 * Deeper paths nest accordingly (`a.b.c` → `{"a":{"b":{"c": …}}}`). When two
 * paths share a prefix their subtrees merge. A path can't be both a leaf and a
 * branch (e.g. `a` and `a.b` together) — the later entry wins its segment,
 * which is a config-authoring error the admin UI prevents rather than a case
 * worth failing the whole read over.
 */
internal object AppConfigTree {

    fun assemble(values: Map<String, JsonElement>): JsonObject {
        val root = LinkedHashMap<String, Any>()
        for ((path, value) in values) {
            val segments = path.split('.').filter { it.isNotEmpty() }
            if (segments.isEmpty()) continue
            insert(root, segments, value)
        }
        return toJsonObject(root)
    }

    private fun insert(node: MutableMap<String, Any>, segments: List<String>, value: JsonElement) {
        val head = segments.first()
        if (segments.size == 1) {
            node[head] = value
            return
        }
        @Suppress("UNCHECKED_CAST")
        val child = node[head] as? MutableMap<String, Any>
            ?: LinkedHashMap<String, Any>().also { node[head] = it }
        insert(child, segments.drop(1), value)
    }

    private fun toJsonObject(node: Map<String, Any>): JsonObject = JsonObject(
        node.mapValues { (_, child) ->
            when (child) {
                is JsonElement -> child
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    toJsonObject(child as Map<String, Any>)
                }
                else -> error("unexpected config tree node: $child")
            }
        },
    )
}
