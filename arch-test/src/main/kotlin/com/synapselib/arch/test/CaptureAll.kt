package com.synapselib.arch.test

import com.synapselib.arch.base.InterceptPoint
import kotlin.reflect.KClass

/**
 * A thread-safe test assertion wrapper that collects **all** values of type
 * [T] observed at one [InterceptPoint] of the harness's SwitchBoard, in the
 * order they were observed.
 *
 * Created by [SynapseTestHarness.onAllImpulses], [SynapseTestHarness.onAllStates],
 * [SynapseTestHarness.onAllRequests], [SynapseTestHarness.onAllRequestResults]
 * and the generic [SynapseTestHarness.captureAll] — or, for values an
 * interceptor **blocked** at the point instead of dispatching them, by
 * [SynapseTestHarness.onBlocked] / [SynapseTestHarness.captureBlocked].
 *
 * Values may be recorded from any thread; every read returns a consistent
 * snapshot.
 *
 * @param T the type of values being captured.
 * @property point the [InterceptPoint] this capture observes.
 * @property type  the type token values must be assignable to.
 */
class CaptureAll<T : Any> internal constructor(
    val point: InterceptPoint,
    val type: KClass<T>,
    private val blocked: Boolean = false,
) {

    /** Where values come from, for assertion messages. */
    private val where: String get() = if (blocked) "${point.describe()} (blocked)" else point.describe()

    /** Guarded by itself. */
    private val list = ArrayList<T>()

    /** Records [data] (already known to be a [T]); called by the harness's observer. */
    internal fun record(data: Any) {
        val value = type.javaObjectType.cast(data)
        synchronized(list) { list.add(value) }
    }

    private fun snapshot(): List<T> = synchronized(list) { list.toList() }

    /** Returns all captured values as an immutable snapshot. */
    val values: List<T> get() = snapshot()

    /** Returns the number of captured values. */
    val count: Int get() = synchronized(list) { list.size }

    /** Returns the most recently captured value, or `null` if none. */
    val latest: T? get() = synchronized(list) { list.lastOrNull() }

    /**
     * Asserts that exactly [n] values were captured.
     *
     * ```kotlin
     * val toasts = synapse.onAllImpulses<ShowToast>()
     * // ... perform actions ...
     * toasts.assertCount(2)
     * ```
     *
     * @throws AssertionError if the number of captured values is not [n].
     */
    fun assertCount(n: Int) {
        val current = snapshot()
        if (current.size != n) throw AssertionError(
            "Expected $n captured value(s) at $where, but got ${current.size}: $current"
        )
    }

    /**
     * Asserts that at least one value was captured, and returns all of them.
     *
     * @throws AssertionError if nothing was captured.
     */
    fun assertCaptured(): List<T> {
        val current = snapshot()
        if (current.isEmpty()) throw AssertionError(
            "Expected at least one captured ${type.simpleName ?: type} at $where"
        )
        return current
    }

    /**
     * Asserts that nothing was captured.
     *
     * @throws AssertionError if any value was captured.
     */
    fun assertNotCaptured() {
        val current = snapshot()
        if (current.isNotEmpty()) throw AssertionError(
            "Values were captured unexpectedly at $where: $current"
        )
    }

    /** Forgets every captured value, so the capture can be reused for a later phase of a test. */
    fun clear() {
        synchronized(list) { list.clear() }
    }

    override fun toString(): String = "CaptureAll<${type.simpleName}>($where, values=${snapshot()})"
}
