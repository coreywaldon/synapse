package com.synapselib.arch.test

import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.SwitchBoardObserver
import com.synapselib.arch.base.TraceContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

/**
 * The single [SwitchBoardObserver] a [SynapseTestHarness] registers on its
 * board. It fans every observed value out to the [Capture] / [CaptureAll]
 * sinks whose [InterceptPoint] and type match.
 *
 * ### Matching
 *
 * A sink for type `T` at point `P` records a value when the value was observed
 * at `P` **and** its *type token* (the `KClass` it travels under — the
 * reified type at the `Broadcast` / `Trigger` / `Request` call site, or the
 * result type for request results) is `T` or a subtype of `T`. This is exactly
 * the rule interceptors use, so a capture sees what an interceptor registered
 * for `T` at `P` would see.
 *
 * ### Blocked values
 *
 * A value an interceptor **blocked** (returned without calling `proceed`) was
 * not dispatched, so it is never recorded by an ordinary sink. Sinks added
 * with [addBlocked] record exactly those values instead (as they entered the
 * blocking stage), via [SwitchBoardObserver.onBlocked].
 *
 * ### Why an observer, not an interceptor
 *
 * Observers run after **all** interceptors of a stage, so captures always see
 * the final, fully processed value — even relative to interceptors registered
 * later or with `Int.MAX_VALUE` priority — and they can never alter,
 * reorder or short-circuit the production interceptor chain.
 *
 * Thread-safe: sinks live in a copy-on-write list and each sink is itself
 * thread-safe.
 */
internal class CaptureHub : SwitchBoardObserver {

    private class Sink(
        val point: InterceptPoint,
        val type: Class<*>,
        val record: (Any) -> Unit,
    )

    /** Sinks for dispatched values ([onData]). */
    private val sinks = CopyOnWriteArrayList<Sink>()

    /** Sinks for blocked values ([onBlocked]). */
    private val blockedSinks = CopyOnWriteArrayList<Sink>()

    /** Adds a sink receiving every value of [type] (or a subtype) dispatched at [point]. */
    fun add(point: InterceptPoint, type: KClass<*>, record: (Any) -> Unit) {
        sinks += Sink(point, type.javaObjectType, record)
    }

    /** Adds a sink receiving every value of [type] (or a subtype) an interceptor blocked at [point]. */
    fun addBlocked(point: InterceptPoint, type: KClass<*>, record: (Any) -> Unit) {
        blockedSinks += Sink(point, type.javaObjectType, record)
    }

    /** Removes every sink. Already captured values are kept by their captures. */
    fun clear() {
        sinks.clear()
        blockedSinks.clear()
    }

    override fun onData(point: InterceptPoint, clazz: KClass<*>, data: Any, trace: TraceContext?) {
        dispatch(sinks, point, clazz, data)
    }

    override fun onBlocked(point: InterceptPoint, clazz: KClass<*>, data: Any, trace: TraceContext?) {
        dispatch(blockedSinks, point, clazz, data)
    }

    private fun dispatch(targets: List<Sink>, point: InterceptPoint, clazz: KClass<*>, data: Any) {
        if (targets.isEmpty()) return
        val token = clazz.javaObjectType
        for (sink in targets) {
            if (sink.point == point && sink.type.isAssignableFrom(token)) sink.record(data)
        }
    }
}
