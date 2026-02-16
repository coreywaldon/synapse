@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)
package com.synapselib.core.typed

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlin.reflect.KClass

/**
 * Lightweight, stateless [SharedFlow] adapter that narrows a `SharedFlow<Any>`
 * to `SharedFlow<T>` using checked [Class.cast] on every element.
 *
 * This avoids unchecked generic casts (`SharedFlow<*> as SharedFlow<T>`) by
 * delegating to the source flow and casting each value individually through
 * [KClass.java].[Class.cast], which throws [ClassCastException] eagerly on
 * a type mismatch rather than failing silently.
 *
 * Instances are cheap (no coroutines, no buffering) and safe to create on
 * every call — the expensive [kotlinx.coroutines.flow.shareIn] work lives in the [source].
 *
 * @param source the underlying untyped shared flow (already cached via `shareIn`).
 * @param clazz  the target type token used for element-wise checked casts.
 */
class TypedSharedFlow<T : Any>(
    private val source: SharedFlow<Any>,
    private val clazz: KClass<T>,
) : SharedFlow<T> {

    override val replayCache: List<T>
        get() = source.replayCache.map { clazz.java.cast(it) }

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.collect { collector.emit(clazz.java.cast(it)) }
    }
}