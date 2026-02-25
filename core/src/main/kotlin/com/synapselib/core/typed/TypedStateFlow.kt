@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)
package com.synapselib.core.typed

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

/**
 * Lightweight, stateless [StateFlow] adapter that narrows a `StateFlow<Any>`
 * to `StateFlow<T>` using checked [Class.cast] on every element.
 *
 * This avoids unchecked generic casts (`StateFlow<*> as StateFlow<T>`) by
 * delegating to the source flow and casting each value individually through
 * [KClass.java].[Class.cast], which throws [ClassCastException] eagerly on
 * a type mismatch rather than failing silently.
 *
 * Instances are cheap (no coroutines, no buffering) and safe to create on
 * every call — the expensive work lives in the [source].
 *
 * @param T      the target element type.
 * @param source the underlying untyped state flow.
 * @param clazz  the target type token used for element-wise checked casts.
 */
class TypedStateFlow<T : Any>(
    private val source: StateFlow<Any>,
    private val clazz: KClass<T>,
) : StateFlow<T> {

    override val value: T
        get() = clazz.java.cast(source.value)

    override val replayCache: List<T>
        get() = source.replayCache.map { clazz.java.cast(it) }

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.collect { collector.emit(clazz.java.cast(it)) }
    }
}