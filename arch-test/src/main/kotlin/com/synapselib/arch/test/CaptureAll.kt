package com.synapselib.arch.test

/**
 * A test assertion wrapper that collects **all** values captured by an
 * interceptor, in order.
 *
 * Created by [SynapseTestRule.onAllImpulses] and [SynapseTestRule.onAllStates].
 *
 * @param T the type of values being captured.
 */
class CaptureAll<T : Any> @PublishedApi internal constructor(private val list: MutableList<T>) {

    /** Returns all captured values as an immutable list. */
    val values: List<T> get() = list.toList()

    /** Returns the number of captured values. */
    val count: Int get() = list.size

    /** Returns the most recently captured value, or `null` if none. */
    val latest: T? get() = list.lastOrNull()

    /**
     * Asserts that exactly [n] values were captured.
     *
     * ```kotlin
     * val toasts = synapse.onAllImpulses<ShowToast>()
     * // ... perform actions ...
     * toasts.assertCount(2)
     * ```
     */
    fun assertCount(n: Int) {
        if (list.size != n) throw AssertionError(
            "Expected $n captured value(s), but got ${list.size}: $list"
        )
    }

    /**
     * Asserts that at least one value was captured, and returns all of them.
     */
    fun assertCaptured(): List<T> {
        if (list.isEmpty()) throw AssertionError("Expected at least one captured value")
        return list.toList()
    }

    /**
     * Asserts that nothing was captured.
     */
    fun assertNotCaptured() {
        if (list.isNotEmpty()) throw AssertionError(
            "Values were captured unexpectedly: $list"
        )
    }
}
