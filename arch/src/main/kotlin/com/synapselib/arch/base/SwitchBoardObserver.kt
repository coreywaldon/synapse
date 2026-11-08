package com.synapselib.arch.base

import kotlin.reflect.KClass

/**
 * A read-only listener that is notified of **every** value passing through a
 * [SwitchBoard], on every channel and in both directions, after the
 * interceptors of that stage have run.
 *
 * Observers are the type-agnostic counterpart to interceptors: use them for
 * logging, metrics, debugging tools or tracing, where registering one
 * interceptor per data type would be impractical. Any number of observers may
 * be registered via [SwitchBoard.addObserver]; each registration is removed
 * independently through its [Registration].
 *
 * ```kotlin
 * val registration = switchBoard.addObserver { point, clazz, data, trace ->
 *     Log.d("${point.channel}/${point.direction}", "${clazz.simpleName}: $data (${trace?.emitterTag})")
 * }
 * // later
 * registration.unregister()
 * ```
 *
 * ## Contract
 *
 * - Callbacks run **synchronously** on the emitting / delivering coroutine,
 *   so they should be fast and non-blocking. Offload expensive work.
 * - Observers are invoked in registration order.
 * - An exception thrown by an observer propagates to the caller of the stage
 *   being observed (e.g. the `broadcastState` caller for upstream points), and
 *   later observers are skipped for that value — exactly as an exception in a
 *   read-only interceptor would.
 * - When no observer is registered the board pays no observation overhead at
 *   all (not even a coroutine-context lookup).
 *
 * ## Evolution
 *
 * This is a `fun interface` with a single abstract method, [onData]. Future
 * notifications will be added as members **with default (no-op)
 * implementations**, so existing observers keep compiling and linking.
 */
fun interface SwitchBoardObserver {

    /**
     * Called for each value that has passed through the interceptors at [point].
     *
     * @param point the stage the value was observed at.
     * @param clazz the type token the value travels under (for requests:
     *              the impulse type upstream and the result type downstream).
     * @param data  the value after interception at [point].
     * @param trace the [TraceContext] of the coroutine that produced or is
     *              consuming the value, or `null` when tracing is not active.
     *              Upstream points carry the emitter's trace; downstream state
     *              and reaction points run inside the board's sharing
     *              coroutines and therefore normally report `null`.
     */
    fun onData(point: InterceptPoint, clazz: KClass<*>, data: Any, trace: TraceContext?)

    /**
     * Called when a **downstream** interceptor (or observer) throws while a
     * state or reaction value is being delivered to listeners.
     *
     * The failing value is dropped — listeners keep their previous value and
     * the channel stays alive for subsequent values — and the [error] is also
     * reported to the board scope's
     * [kotlinx.coroutines.CoroutineExceptionHandler] (or the platform default),
     * so it is never silently swallowed.
     *
     * The default implementation does nothing.
     *
     * @param point the downstream stage that failed.
     * @param clazz the type token of the dropped value.
     * @param data  the value as it entered the failing stage.
     * @param error the exception that was thrown.
     */
    fun onDeliveryFailure(point: InterceptPoint, clazz: KClass<*>, data: Any, error: Throwable) {}
}
