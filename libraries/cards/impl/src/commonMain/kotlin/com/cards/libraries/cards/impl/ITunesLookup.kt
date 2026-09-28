package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppVersion
import com.dangerfield.cards.libraries.core.Catching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The live App Store version out of an iTunes lookup response
 * (`https://itunes.apple.com/lookup?bundleId=…`), or null when the body isn't
 * a lookup hit. Kept separate from the iOS network call so it can be pinned
 * on the JVM.
 */
internal fun iTunesLookupVersion(body: String): AppVersion? = Catching {
    val results = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
    val version = results?.firstOrNull()?.jsonObject?.get("version")?.jsonPrimitive?.content
    AppVersion.parseOrNull(version)
}.getOrNull()
