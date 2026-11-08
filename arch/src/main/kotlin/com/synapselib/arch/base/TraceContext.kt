package com.synapselib.arch.base

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
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
 * All fields have sensible defaults. When a [SynapseScope] (a [CoordinatorScope],
 * [ContextScope], [NodeScope] or provider scope) has a non-null `tag`, it
 * automatically injects a fresh `TraceContext` via `withContext` around each
 * emission. Scopes without a tag pay zero overhead — no context element is
 * created and no context lookup is performed.
 *
 * ## Causal chains
 *
 * Every trace point created by a tagged scope (or by [withTrace] /
 * [childOf]) records the [traceId] of the trace already present in the
 * calling coroutine as its [parentTraceId]. Following `parentTraceId` links
 * therefore reconstructs the chain of emissions that happened *within* a
 * coroutine (or a coroutine and the coroutines it launched, which inherit the
 * context). Traces are **not** carried across the [SwitchBoard] hop to
 * listeners — see [SynapseScope] for the full propagation rules.
 *
 * @property traceId       unique identifier for this trace point (monotonic counter).
 * @property parentTraceId [traceId] of the trace that was active when this one
 *                         was created, or `null` for a root trace.
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

    /**
     * Creates a new trace point whose [parentTraceId] is this trace's [traceId].
     *
     * @param emitterTag label for the child; defaults to this trace's [emitterTag].
     */
    fun child(emitterTag: String? = this.emitterTag): TraceContext =
        TraceContext(parentTraceId = traceId, emitterTag = emitterTag)

    companion object Key : CoroutineContext.Key<TraceContext> {
        private val counter = AtomicLong(0)

        /** Returns a new process-unique, monotonically increasing trace id. */
        fun nextTraceId(): String = "trace-${counter.incrementAndGet()}"

        /**
         * Creates a new trace point tagged [emitterTag], linked to [parent]
         * when one is given (a root trace otherwise).
         */
        fun childOf(parent: TraceContext?, emitterTag: String?): TraceContext =
            TraceContext(parentTraceId = parent?.traceId, emitterTag = emitterTag)
    }
}

// ── Tracing helpers ─────────────────────────────────────────────────────

/** Returns the [TraceContext] carried by the current coroutine, if any. */
suspend fun currentTraceContext(): TraceContext? = currentCoroutineContext()[TraceContext]

/**
 * Runs [block] inside a new [TraceContext] tagged [emitterTag] whose
 * [TraceContext.parentTraceId] links to the current coroutine's trace (if any).
 *
 * Every [SwitchBoard] emission made inside [block] — directly, through an
 * untagged scope, or through a tagged scope (which creates a further child) —
 * is reported against this trace or one of its descendants:
 *
 * ```kotlin
 * withTrace("CheckoutFlow") {
 *     Broadcast(CartLocked)          // reported with CheckoutFlow's trace (untagged scope)
 *     taggedCoordinator.Trigger(Pay) // new trace, parentTraceId = CheckoutFlow's traceId
 * }
 * ```
 *
 * @param emitterTag label for the new trace point.
 * @param block      the work to run inside the trace.
 * @return the result of [block].
 */
suspend fun <R> withTrace(
    emitterTag: String?,
    block: suspend CoroutineScope.() -> R,
): R = withContext(TraceContext.childOf(currentTraceContext(), emitterTag), block)
