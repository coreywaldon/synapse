package com.synapselib.arch.base

import kotlin.reflect.KClass

/**
 * A read-only listener that is notified of **every** value passing through a
 * [SwitchBoard], on every channel and in both directions, after the
 * interceptors of that stage have run. Values an interceptor **blocked** (did
 * not pass on with `proceed`) are not dispatched and are reported through
 * [onBlocked] rather than [onData].
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
 * This is a `fun interface` with a single abstract method, [onData]. Further
 * notifications ([onDeliveryFailure], [onBlocked], …) are members **with
 * default (no-op) implementations**, so existing observers keep compiling and
 * linking.
 */
fun interface SwitchBoardObserver {

    /**
     * Called for each value that has passed through the interceptors at [point]
     * and is dispatched. Not called for values an interceptor blocked (see
     * [onBlocked]).
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

    /**
     * Called when an interceptor at [point] **blocked** a value, i.e. returned
     * without calling `proceed`, so the end of the chain was never reached and
     * the value was not dispatched (not emitted, not sent to a provider, or not
     * delivered to listeners — see [SwitchBoard.addInterceptor]).
     *
     * A blocked value is reported here *instead of* through [onData]. Useful
     * for tooling and tests that want to see gates and filters fire.
     *
     * Invoked under the same contract as [onData] (synchronously, in
     * registration order, exceptions propagate to the stage).
     *
     * The default implementation does nothing.
     *
     * @param point the stage whose interceptors blocked the value.
     * @param clazz the type token the value travels under.
     * @param data  the value **as it entered** the stage; whatever the
     *              blocking interceptor returned is discarded.
     * @param trace the [TraceContext] of the stage's coroutine, or `null`
     *              (see [onData]).
     * @since 1.1.0
     */
    fun onBlocked(point: InterceptPoint, clazz: KClass<*>, data: Any, trace: TraceContext?) {}
}
