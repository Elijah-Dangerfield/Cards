package com.dangerfield.cards.libraries.core

/**
 * Applies the given arguments to the string. The arguments are expected to be in the format
 * {key} and will be replaced with the value of the key in the map.
 */
fun String.applyArgs(args: Map<String,String>): String {
    var result = this
    args.forEach { (key, value) ->
        Catching {
            result = result.replace("{$key}", value)
        }
            .logOnFailure()
    }
    return result
}

fun String.applyArgs(vararg args: Pair<String,String>): String {
    var result = this
    args.forEach { (key, value) ->
        Catching {
            result = result.replace("{$key}", value)
        }
            .logOnFailure()
    }
    return result
}