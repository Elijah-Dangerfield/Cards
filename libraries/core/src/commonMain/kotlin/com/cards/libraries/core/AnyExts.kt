package com.dangerfield.cards.libraries.core

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Convenience method for only executing logic if the parameters are not null
 */
inline fun <A, B, T> allOrNone(one: A?, two: B?, block: (A, B) -> T): T? = if (one != null && two != null) block(one, two) else null

/**
 * Convenience method for only executing logic if the parameters are not null
 */
@OptIn(ExperimentalContracts::class)
inline fun <A, B, C, T> allOrNone(one: A?, two: B?, three: C?, block: (A, B, C) -> T): T? {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (one != null && two != null && three != null) block(one, two, three) else null
}

/**
 * Convenience method for only executing logic if the parameters are not null
 */
fun <A, B, C, D, T> allOrNone(one: A?, two: B?, three: C?, four: D?, block: (A, B, C, D) -> T): T? =
    if (one != null && two != null && three != null && four != null) block(one, two, three, four) else null

/**
 * Convenience Method to make it more readable when no logic needs to be executed
 */
fun Any.doNothing() = Unit
fun doNothing() = Unit

///**
// * Convenience method for converting Any to a specific type
// */
//@Suppress("UNCHECKED_CAST")
//fun Any?.convertTo(clazz: Class<*>): Any? {
//    return when (clazz) {
//        Boolean::class.java -> this?.toString()?.toBoolean()
//        Int::class.java -> this?.toString()?.toIntOrNull()
//        Double::class.java -> this?.toString()?.toDoubleOrNull()
//        Float::class.java -> this?.toString()?.toFloatOrNull()
//        Byte::class.java -> this?.toString()?.toByteOrNull()
//        Short::class.java -> this?.toString()?.toShortOrNull()
//        Long::class.java -> this?.toString()?.toLongOrNull()
//        else -> this
//    }
//}