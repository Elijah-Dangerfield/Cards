package com.dangerfield.cards.libraries.networking

import com.dangerfield.cards.libraries.core.BuildInfo
import kotlinx.serialization.json.Json

/**
 * The single [Json] instance every network call should use. Two modes:
 *
 * - **Debug**: strict. Unknown keys throw, missing required fields throw,
 *   coerced inputs throw. The point is to catch backend/contract drift loud
 *   and early during development.
 * - **Release**: lenient. Unknown keys are ignored, missing fields fall back
 *   to defaults, malformed-but-recoverable inputs are coerced. Real users
 *   should not crash because the server added a new field.
 *
 * `explicitNulls = false` in both modes — null fields are omitted from
 * serialization output, which matches what most APIs expect.
 *
 * If you need a different config for one specific call, build a local [Json]
 * — but most app code should use this.
 */
val NetworkJson: Json = Json {
    ignoreUnknownKeys = !BuildInfo.isDebug
    isLenient = !BuildInfo.isDebug
    coerceInputValues = !BuildInfo.isDebug
    explicitNulls = false
    encodeDefaults = false
}
