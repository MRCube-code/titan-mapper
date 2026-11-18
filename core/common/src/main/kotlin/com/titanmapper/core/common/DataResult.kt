package com.titanmapper.core.common

import kotlinx.coroutines.CancellationException

/**
 * Explicit success/failure channel used across repository and service boundaries so callers
 * handle errors in the type system instead of catching exceptions they don't know about.
 */
sealed interface DataResult<out T> {
    data class Success<T>(val value: T) : DataResult<T>

    /**
     * @param message safe to show to the user
     * @param recoverable true when retrying or changing input can succeed (e.g. wrong passphrase)
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val recoverable: Boolean = true
    ) : DataResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value

    fun <R> map(transform: (T) -> R): DataResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Error -> this
    }

    fun <R> fold(onSuccess: (T) -> R, onError: (Error) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Error -> onError(this)
    }
}

/** Runs [block], converting any exception except cancellation into [DataResult.Error]. */
inline fun <T> safeCall(errorMessage: String, block: () -> T): DataResult<T> = try {
    DataResult.Success(block())
} catch (e: CancellationException) {
    throw e // never swallow coroutine cancellation
} catch (e: Exception) {
    DataResult.Error(errorMessage, e)
}
