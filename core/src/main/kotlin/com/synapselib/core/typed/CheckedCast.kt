package com.synapselib.core.typed

import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * KMP-safe checked cast. Delegates to [KClass.safeCast] and throws
 * [ClassCastException] eagerly on mismatch — mirroring `Class.cast` semantics
 * without depending on JVM-only reflection.
 */
internal fun <T : Any> KClass<T>.checkedCast(value: Any): T =
    safeCast(value)
        ?: throw ClassCastException("Cannot cast ${value::class} to $this")

/**
 * Narrows `DataState<*>` to `DataState<T>` by checked-casting the inner data
 * values ([DataState.Success.data], [DataState.Error.staleData]). Variants
 * without data are returned unchanged.
 */
internal fun <T : Any> DataState<*>.checkedCast(clazz: KClass<T>): DataState<T> =
    when (this) {
        is DataState.Idle -> DataState.Idle
        is DataState.Loading -> DataState.Loading
        is DataState.Success -> DataState.Success(clazz.checkedCast(data))
        is DataState.Error<*> -> DataState.Error(
            cause = cause,
            staleData = staleData?.let { clazz.checkedCast(it) },
        )
    }
