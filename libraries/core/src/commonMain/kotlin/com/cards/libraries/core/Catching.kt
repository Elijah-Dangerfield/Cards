package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.libraries.core.logging.KLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlin.reflect.KClass

typealias Catching<T> = Result<T>

inline fun <T> Catching(f: () -> T): Catching<T> = runCatching(f)
    .onFailure {
        if (it.shouldNotBeCaught) throw it
    }

fun <T> Catching<T>.logOnFailure(message: () -> String? = {null}): Catching<T> = onFailure {
    KLog.e(it) { message() ?: "Catching Failure" }
}
fun <T> Catching<T>.logOnFailure(message:  String): Catching<T> = onFailure {
    KLog.e(it) { message }
}

fun <T> Catching<T>.getExceptionOrNull(): Throwable? = exceptionOrNull()

fun <T> Catching<T>.throwIfDebug(): Catching<T> = onFailure {
    if (BuildInfo.isDebug && this.isFailure) {
        throw DebugException(it)
    }
}

inline fun illegalStateFailure(lazyMessage: () -> String) =
    Catching.failure<Nothing>(IllegalStateException(lazyMessage()))

fun <T> Flow<T>.asCatching(): Flow<Catching<T>> = map {
    Catching { it }
}.catch { emit(Result.failure(it)) }


fun <T> T.success(): Catching<T> = Catching.success(this)
fun success(): Catching<Unit> = Catching.success(Unit)

fun failure(throwable: Throwable): Catching<Nothing> = Catching.failure(throwable)

inline fun <T> Catching<T>.eitherWay(block: (Catching<T>) -> Unit) = this.also(block)

fun <T> Catching<T>.ignoreValue(): Catching<Unit> = this.map { }
fun <T> Catching<T>.ignore() = doNothing()

inline fun <T> Catching<T>.mapFailure(f: (Throwable) -> Throwable): Catching<T> {
    return when (val exception = exceptionOrNull()) {
        null -> this
        else -> Result.failure(f(exception))
    }
}

inline fun <T> List<Catching<T>>.failFast(): Catching<T> {
    return this.firstOrNull() { it.isFailure } ?: this.last()
}

inline fun <T, R> Catching<T>.flatMap(f: (right: T) -> Catching<R>): Catching<R> {
    val exception = exceptionOrNull()
    return when {
        exception != null -> Catching.failure(exception)
        else -> f(getOrThrow())
    }
}

inline fun <T, reified E : Throwable> Catching<T>.recoverFrom(
    error: KClass<E>,
    f: (E) -> Catching<T>,
): Catching<T> {
    val exception = exceptionOrNull()

    return when {
        exception != null && exception::class.isInstance(error) -> Catching { f(exception as E).getOrThrow() }
        else -> this
    }
}


