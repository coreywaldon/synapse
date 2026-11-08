package com.synapselib.core.typed

import kotlinx.serialization.Serializable

/**
 * The lifecycle of a piece of fetched data: [Idle] → [Loading] → [Success] or [Error].
 *
 * Providers emit `DataState` through `Request(...)`, and screens usually keep it as-is in their
 * state so the UI can render every phase:
 *
 * ```kotlin
 * when (val products = state.products) {
 *     DataState.Idle, DataState.Loading -> Spinner()
 *     is DataState.Success -> ProductList(products.data)
 *     is DataState.Error -> ErrorBanner(products.cause.message, stale = products.staleData)
 * }
 * ```
 *
 * ## Serialization
 *
 * `DataState` is `@Serializable` through [DataStateSerializer], so a `@Serializable` class may hold
 * one — e.g. `val products: DataState<List<Product>> = DataState.Idle` — as long as the data type
 * `T` is itself serializable. This is what lets a Synapse `Node` save such state across
 * configuration changes and process death. Each variant is encoded as a small discriminated
 * structure (shown as JSON):
 *
 * ```json
 * {"state":"idle"}
 * {"state":"loading"}
 * {"state":"success","data":<T>}
 * {"state":"error","message":"timeout","type":"java.io.IOException","staleData":<T>}
 * ```
 *
 * [Idle] and [Loading] decode to the same singletons. A [Throwable] is not serializable, so an
 * [Error] saves only its cause's class name and message, and is restored with a
 * [RestoredDataStateException] cause carrying them; the cause's identity, type and stack trace are
 * not preserved, while [Error.staleData] is. A live `Request` re-emits the real state once it runs
 * again after restoration, so the restored value is only what the UI shows until then.
 *
 * Only the `DataState<T>` type itself is serializable: a property declared as a subtype
 * (`DataState.Success<T>`) is not.
 *
 * @param T the type of the fetched data.
 */
@Serializable(with = DataStateSerializer::class)
sealed interface DataState<out T : Any> {

    /** No request has been made yet. Initial state before activation. */
    data object Idle : DataState<Nothing>

    /** The flow is actively fetching data. */
    data object Loading : DataState<Nothing>

    /**
     * The flow successfully produced a result.
     *
     * @property data the fetched value.
     */
    data class Success<T : Any>(val data: T) : DataState<T>

    /**
     * The flow encountered an error during production.
     *
     * After a save/restore round trip through [DataStateSerializer], [cause] is a
     * [RestoredDataStateException] carrying the original cause's class name and message.
     *
     * @property cause    the exception that caused the failure.
     * @property staleData the last successful value, if available. Allows
     *                     UI to show stale content alongside an error indicator.
     */
    data class Error<T : Any>(
        val cause: Throwable,
        val staleData: T? = null,
    ) : DataState<T>
}

/** Returns the [DataState.Success.data] or `null` if not in a success state. */
val <T : Any> DataState<T>.dataOrNull: T?
    get() = (this as? DataState.Success)?.data

/** Returns `true` when in [DataState.Loading]. */
val DataState<*>.isLoading: Boolean
    get() = this is DataState.Loading
