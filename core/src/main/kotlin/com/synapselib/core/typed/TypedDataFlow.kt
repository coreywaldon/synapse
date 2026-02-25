@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

package com.synapselib.core.typed

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

/**
 * Lightweight, stateless [StateFlow] adapter that narrows a
 * `StateFlow<DataState<*>>` to `StateFlow<DataState<T>>` using checked
 * [Class.cast] on the **inner data values**.
 *
 * Because `DataState` is a sealed interface with covariant type parameter
 * (`DataState<out T>`), the outer class erases identically regardless of
 * `T`. A raw `StateFlow<*> as StateFlow<DataState<T>>` cast would
 * succeed trivially at the outer level while leaving the inner
 * [DataState.Success.data] and [DataState.Error.staleData] unchecked.
 *
 * This adapter solves that by casting each emitted [DataState] element-wise:
 * - [DataState.Idle] and [DataState.Loading] carry no data — passed through.
 * - [DataState.Success]: `data` is cast via [Class.cast].
 * - [DataState.Error]: `staleData` (if non-null) is cast via [Class.cast].
 *
 * Throws [ClassCastException] eagerly on a type mismatch rather than
 * failing silently downstream.
 *
 * Instances are inexpensive (no coroutines, no buffering) and safe to create on
 * every call — the expensive work lives in the [source].
 *
 * @param T      the target element type for the [DataState] wrapper.
 * @param source the underlying erased state flow producing [DataState] values.
 * @param clazz  the target type token used for inner data checked casts.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class TypedDataStateFlow<T : Any>(
    private val source: StateFlow<DataState<*>>,
    private val clazz: KClass<T>,
) : StateFlow<DataState<T>> {

    override val value: DataState<T>
        get() = source.value.checkedCast()

    override val replayCache: List<DataState<T>>
        get() = source.replayCache.map { it.checkedCast() }

    override suspend fun collect(collector: FlowCollector<DataState<T>>): Nothing {
        source.collect { collector.emit(it.checkedCast()) }
    }

    /**
     * Performs a checked narrowing of `DataState<*>` to `DataState<T>` by
     * casting the inner data values through [Class.cast].
     *
     * - [DataState.Idle] → returned as-is (no data to cast).
     * - [DataState.Loading] → returned as-is (no data to cast).
     * - [DataState.Success] → `data` is cast to [T] via [Class.cast].
     * - [DataState.Error] → `staleData` (if non-null) is cast to [T]
     *   via [Class.cast].
     *
     * @return the same [DataState] variant with its data verified as [T].
     * @throws ClassCastException if any inner data value is not assignable to [T].
     */
    private fun DataState<*>.checkedCast(): DataState<T> {
        val javaClass = clazz.java
        return when (this) {
            is DataState.Idle -> DataState.Idle
            is DataState.Loading -> DataState.Loading
            is DataState.Success -> DataState.Success(javaClass.cast(data))
            is DataState.Error<*> -> DataState.Error(
                cause = cause,
                staleData = staleData?.let { javaClass.cast(it) },
            )
        }
    }
}

/**
 * Lightweight, stateless [SharedFlow] adapter that narrows a
 * `SharedFlow<DataState<*>>` to `SharedFlow<DataState<T>>` using checked
 * [Class.cast] on the **inner data values**.
 *
 * Because `DataState` is a sealed interface with covariant type parameter
 * (`DataState<out T>`), the outer class erases identically regardless of
 * `T`. A raw `SharedFlow<*> as SharedFlow<DataState<T>>` cast would
 * succeed trivially at the outer level while leaving the inner
 * [DataState.Success.data] and [DataState.Error.staleData] unchecked.
 *
 * This adapter solves that by casting each emitted [DataState] element-wise:
 * - [DataState.Idle] and [DataState.Loading] carry no data — passed through.
 * - [DataState.Success]: `data` is cast via [Class.cast].
 * - [DataState.Error]: `staleData` (if non-null) is cast via [Class.cast].
 *
 * Throws [ClassCastException] eagerly on a type mismatch rather than
 * failing silently downstream.
 *
 * Instances are cheap (no coroutines, no buffering) and safe to create on
 * every call — the expensive work lives in the [source].
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
        get() = source.replayCache.map { it.checkedCast() }

    override suspend fun collect(collector: FlowCollector<DataState<T>>): Nothing {
        source.collect { collector.emit(it.checkedCast()) }
    }

    /**
     * Performs a checked narrowing of `DataState<*>` to `DataState<T>` by
     * casting the inner data values through [Class.cast].
     *
     * - [DataState.Idle] → returned as-is (no data to cast).
     * - [DataState.Loading] → returned as-is (no data to cast).
     * - [DataState.Success] → `data` is cast to [T] via [Class.cast].
     * - [DataState.Error] → `staleData` (if non-null) is cast to [T]
     *   via [Class.cast].
     *
     * @return the same [DataState] variant with its data verified as [T].
     * @throws ClassCastException if any inner data value is not assignable to [T].
     */
    private fun DataState<*>.checkedCast(): DataState<T> {
        val javaClass = clazz.java
        return when (this) {
            is DataState.Idle -> DataState.Idle
            is DataState.Loading -> DataState.Loading
            is DataState.Success -> DataState.Success(javaClass.cast(data))
            is DataState.Error<*> -> DataState.Error(
                cause = cause,
                staleData = staleData?.let { javaClass.cast(it) },
            )
        }
    }
}
