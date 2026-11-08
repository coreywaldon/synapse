package com.synapselib.core.typed

import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * KMP-safe checked cast. Delegates to [KClass.safeCast] and throws
 * [ClassCastException] eagerly on mismatch — mirroring `Class.cast` semantics
 * without depending on JVM-only reflection.
 *
 * @param context describes where the value came from (e.g. `"TypedSharedFlow element"`),
 *                so the error points at the adapter and slot that received the wrong type.
 */
internal fun <T : Any> KClass<T>.checkedCast(value: Any, context: String = "value"): T =
    safeCast(value) ?: throw ClassCastException(
        "$context: expected an instance of ${typeName()} but got ${value::class.typeName()}. " +
            "This usually means two different types were published under the same key/channel."
    )

private fun KClass<*>.typeName(): String = qualifiedName ?: simpleName ?: toString()

/**
 * Narrows `DataState<*>` to `DataState<T>` by checked-casting the inner data
 * values ([DataState.Success.data], [DataState.Error.staleData]). Variants
 * without data are returned unchanged.
 */
internal fun <T : Any> DataState<*>.checkedCast(
    clazz: KClass<T>,
    context: String = "DataState",
): DataState<T> =
    when (this) {
        is DataState.Idle -> DataState.Idle
        is DataState.Loading -> DataState.Loading
        is DataState.Success -> DataState.Success(clazz.checkedCast(data, "$context Success.data"))
        is DataState.Error<*> -> DataState.Error(
            cause = cause,
            staleData = staleData?.let { clazz.checkedCast(it, "$context Error.staleData") },
        )
    }
