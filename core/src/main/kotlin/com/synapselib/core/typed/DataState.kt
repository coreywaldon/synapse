package com.synapselib.core.typed

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