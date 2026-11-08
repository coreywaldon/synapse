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

    private val sinks = CopyOnWriteArrayList<Sink>()

    /** Adds a sink receiving every value of [type] (or a subtype) observed at [point]. */
    fun add(point: InterceptPoint, type: KClass<*>, record: (Any) -> Unit) {
        sinks += Sink(point, type.javaObjectType, record)
    }

    /** Removes every sink. Already captured values are kept by their captures. */
    fun clear() {
        sinks.clear()
    }

    override fun onData(point: InterceptPoint, clazz: KClass<*>, data: Any, trace: TraceContext?) {
        if (sinks.isEmpty()) return
        val token = clazz.javaObjectType
        for (sink in sinks) {
            if (sink.point == point && sink.type.isAssignableFrom(token)) sink.record(data)
        }
    }
}
