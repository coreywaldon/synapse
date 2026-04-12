@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

package com.synapselib.core.typed

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

/**
 * Lightweight, stateless [StateFlow] adapter that narrows a
 * `StateFlow<DataState<*>>` to `StateFlow<DataState<T>>` using a checked
 * cast on the **inner data values**.
 *
 * Because `DataState` is a sealed interface with covariant type parameter
 * (`DataState<out T>`), the outer class erases identically regardless of
 * `T`. A raw `StateFlow<*> as StateFlow<DataState<T>>` cast would succeed
 * trivially at the outer level while leaving the inner
 * [DataState.Success.data] and [DataState.Error.staleData] unchecked.
 *
 * This adapter casts each emitted [DataState] element-wise and throws
 * [ClassCastException] eagerly on a type mismatch.
 *
 * Instances are inexpensive (no coroutines, no buffering) and safe to create
 * on every call — the expensive work lives in the [source].
 *
 * @param T      the target element type for the [DataState] wrapper.
 * @param source the underlying erased state flow producing [DataState] values.
 * @param clazz  the target type token used for inner data checked casts.
 */
class TypedDataStateFlow<T : Any>(
    private val source: StateFlow<DataState<*>>,
    private val clazz: KClass<T>,
) : StateFlow<DataState<T>> {

    override val value: DataState<T>
        get() = source.value.checkedCast(clazz)

    override val replayCache: List<DataState<T>>
        get() = source.replayCache.map { it.checkedCast(clazz) }

    override suspend fun collect(collector: FlowCollector<DataState<T>>): Nothing {
        source.collect { collector.emit(it.checkedCast(clazz)) }
    }
}

/**
 * Lightweight, stateless [SharedFlow] adapter that narrows a
 * `SharedFlow<DataState<*>>` to `SharedFlow<DataState<T>>` using a checked
 * cast on the **inner data values**.
 *
 * See [TypedDataStateFlow] for the full rationale — this is the
 * [SharedFlow] variant with identical semantics.
 *
 * @param T      the target element type for the [DataState] wrapper.
 * @param source the underlying erased shared flow producing [DataState] values.
 * @param clazz  the target type token used for inner data checked casts.
 */
class TypedDataSharedFlow<T : Any>(
    private val source: SharedFlow<DataState<*>>,
    private val clazz: KClass<T>,
) : SharedFlow<DataState<T>> {

    override val replayCache: List<DataState<T>>
        get() = source.replayCache.map { it.checkedCast(clazz) }

    override suspend fun collect(collector: FlowCollector<DataState<T>>): Nothing {
        source.collect { collector.emit(it.checkedCast(clazz)) }
    }
}
