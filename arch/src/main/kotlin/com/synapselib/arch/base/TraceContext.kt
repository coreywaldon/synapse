package com.synapselib.arch.base

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A [CoroutineContext.Element] that carries tracing metadata through the
 * coroutine context, allowing the [SwitchBoard] to report **where** an
 * impulse or state broadcast originated.
 *
 * `TraceContext` is never stored in the `SharedFlow` — it travels alongside
 * the coroutine that calls [SwitchBoard.triggerImpulse] or
 * [SwitchBoard.broadcastState], and is read by [DefaultSwitchBoard.processAndLog]
 * to invoke the trace listener.
 *
 * ## Opt-in
 *
 * All fields have sensible defaults. When a [CoordinatorScope] or
 * [ContextScope] has a non-null `tag`, they automatically inject a
 * `TraceContext` via `withContext`. Callers that don't set a tag pay
 * zero overhead — no context element is created.
 *
 * @property traceId       unique identifier for this trace point (monotonic counter).
 * @property parentTraceId optional link to a parent trace (for chained operations).
 * @property emitterTag    human-readable label identifying the emitter
 *                         (e.g., `"AuthCoordinator"`, `"CheckoutScreen"`).
 * @property timestamp     monotonic timestamp (via [System.nanoTime]) of creation.
 */
data class TraceContext(
    val traceId: String = nextTraceId(),
    val parentTraceId: String? = null,
    val emitterTag: String? = null,
    val timestamp: Long = System.nanoTime(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceContext> {
        private val counter = AtomicLong(0)
        fun nextTraceId(): String = "trace-${counter.incrementAndGet()}"
    }
}
