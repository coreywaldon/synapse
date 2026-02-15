package com.synapselib.arch.base

import com.synapselib.arch.base.routing.RequestParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Creates a long-lived [CoordinatorScope] and runs [block] with it as the
 * receiver, mirroring the [Node] DSL for non-Compose contexts.
 *
 * This is the primary entry point for wiring up [SwitchBoard] communication
 * outside a Compose composition — typically from an activity, service,
 * or background process.
 *
 *
 * @param switchboard the [SwitchBoard] this coordinator communicates through.
 * @param scope       optional externally-managed [CoroutineScope] (e.g.
 *                    `viewModelScope`). If not provided, a new scope with a
 *                    [SupervisorJob] on [Dispatchers.Main.immediate] is created.
 *                    When an external scope is supplied, [CoordinatorScope.dispose]
 *                    will cancel only the jobs it owns; the parent scope's
 *                    lifecycle is unaffected.
 * @param block       initialization block — wire up listeners, interceptors, and
 *                    initial broadcasts here. Runs synchronously before the
 *                    function returns.
 * @return the fully initialized [CoordinatorScope]. Call
 *         [CoordinatorScope.dispose] to cancel all jobs and unregister all
 *         interceptors.
 */
fun Coordinator(
    switchboard: SwitchBoard,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    block: CoordinatorScope.() -> Unit,
): CoordinatorScope = CoordinatorScope(switchboard, scope).apply(block)

/**
 * A long-lived, non-Compose counterpart to [NodeScope].
 *
 * Provides the same [SwitchBoard] capabilities — broadcasting state,
 * triggering reactions, listening, requesting, and intercepting — with a
 * **manually managed lifecycle** instead of Compose's `DisposableEffect`.
 *
 * ## Capabilities
 *
 * Each consumption method has two overloads:
 * - **Flow overload**: returns the [SharedFlow] directly for the caller to
 *   collect, combine, filter, or transform as needed.
 * - **Handler overload**: launches a coroutine that collects the flow and
 *   delivers each value to a callback. Returns a [Job] that is canceled
 *   on [dispose].
 *
 * | Category | Flow overload | Handler overload |
 * |---|---|---|
 * | **State broadcast** | — | [Broadcast] (suspending) |
 * | **Reactions** | — | [Trigger] (suspending) |
 * | **Interception** | — | [Intercept] (returns [Registration]) |
 * | **Listen (state)** | [ListenFor]`()` → `SharedFlow<O>` | [ListenFor]`{ handler }` → [Job] |
 * | **Listen (reaction)** | [ReactTo]`()` → `SharedFlow<A>` | [ReactTo]`{ handler }` → [Job] |
 * | **Request** | [Request]`(params)` → `SharedFlow<Need>` | [Request]`(params) { callback }` → [Job] |
 *
 * ## CoroutineScope Delegation
 *
 * Implements [CoroutineScope] by delegation, so standard coroutine builders
 * (`launch`, `async`, etc.) are available directly on the scope and inside
 * handler receivers:
 *
 * ```kotlin
 * val coordinator = Coordinator(switchboard) {
 *     ReactTo<DataRefreshRequested> {
 *         launch { Broadcast(fetchLatestData()) }
 *     }
 * }
 * ```
 *
 * ## Lifecycle
 *
 * Call [dispose] when the coordinator is no longer needed. This will:
 * 1. Cancel the backing coroutine job (and all child jobs/listeners).
 * 2. Unregister all interceptors added via [Intercept].
 * 3. Clear the internal registration list.
 *
 * @param switchboard    the [SwitchBoard] this scope communicates through.
 * @param coroutineScope the backing [CoroutineScope] for structured concurrency.
 *                       Defaults to a new scope with [SupervisorJob] on
 *                       [Dispatchers.Main].
 */
class CoordinatorScope(
    val switchboard: SwitchBoard,
    coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : CoroutineScope by coroutineScope {

    /**
     * Accumulated interceptor registrations that will be
     * [unregistered][Registration.unregister] on [dispose].
     */
    @PublishedApi
    internal val registrations = mutableListOf<Registration>()

    // ── Interceptor Registration ────────────────────────────────────────

    /**
     * Registers an [interceptor] at the given [point] for data of type [T],
     * tracking the [Registration] for automatic cleanup on [dispose].
     *
     * ```kotlin
     * Intercept<AnalyticsEvent>(
     *     point = InterceptPoint(Channel.REACTION, Direction.UPSTREAM),
     *     interceptor = Interceptor.read { event -> analytics.track(event) },
     * )
     * ```
     *
     * @param T           the data type the interceptor operates on (inferred).
     * @param point       the [InterceptPoint] (channel + direction) to intercept.
     * @param interceptor the [Interceptor] to install.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     * @return the [Registration] handle, also stored internally for cleanup.
     */
    inline fun <reified T : Any> Intercept(
        point: InterceptPoint,
        interceptor: Interceptor<T>,
        priority: Int = 0,
    ): Registration {
        val registration = switchboard.addInterceptor(point, T::class, interceptor, priority)
        registrations.add(registration)
        return registration
    }

    // ── Imperative Actions ──────────────────────────────────────────────

    /**
     * Broadcasts [data] into the [SwitchBoard]'s state channel.
     *
     * Upstream state interceptors are applied before emission.
     *
     * @param O    the state type (inferred).
     * @param data the value to broadcast.
     * @see SwitchBoard.broadcastState
     */
    suspend inline fun <reified O : Any> Broadcast(data: O) {
        switchboard.broadcastState(data)
    }

    /**
     * Triggers a fire-and-forget [Impulse] on the [SwitchBoard]'s reaction
     * channel.
     *
     * Upstream reaction interceptors are applied before emission.
     *
     * @param A     the [Impulse] subtype (inferred).
     * @param event the impulse event to emit.
     * @see SwitchBoard.triggerImpulse
     */
    suspend inline fun <reified A : Impulse> Trigger(event: A) {
        switchboard.triggerImpulse(event)
    }

    // ── Flow Access (return the flow directly) ──────────────────────────

    /**
     * Returns the downstream-intercepted [SharedFlow] for state values of
     * type [O].
     *
     * Use this overload when you need to compose, filter, debounce, or
     * otherwise transform the flow before collecting:
     *
     * ```kotlin
     * val configFlow = ListenFor<AppConfig>()
     *
     * launch {
     *     combine(configFlow, ListenFor<FeatureFlags>()) { config, flags ->
     *         // …
     *     }.collectLatest { /* react */ }
     * }
     * ```
     *
     * @param O the state type (inferred).
     * @return a [SharedFlow] with `replay = 1` emitting intercepted state values.
     */
    inline fun <reified O : Any> ListenFor(): SharedFlow<O> =
        switchboard.stateFlow(O::class)

    /**
     * Returns the downstream-intercepted [SharedFlow] for impulse events of
     * type [A].
     *
     * Use this overload when you need to compose or transform the flow:
     *
     * ```kotlin
     * val toastFlow = ReactTo<ShowToast>()
     *
     * launch {
     *     toastFlow.filter { it.priority == HIGH }.collect { /* show toast */ }
     * }
     * ```
     *
     * @param A the [Impulse] subtype (inferred).
     * @return a [SharedFlow] with `replay = 0` emitting intercepted impulse events.
     */
    inline fun <reified A : Impulse> ReactTo(): SharedFlow<A> =
        switchboard.impulseFlow(A::class)

    /**
     * Returns the downstream-intercepted [SharedFlow] for the result of
     * routing [params] through the [SwitchBoard]'s request pipeline.
     *
     * Use this overload when you need access to the raw flow:
     *
     * ```kotlin
     * val resultFlow = Request<UserProfile, FetchUserParams>(
     *     params = FetchUserParams(userId = 42),
     * )
     *
     * launch {
     *     resultFlow.collectLatest { profile ->
     *         Broadcast(profile)
     *     }
     * }
     * ```
     *
     * @param Need the expected result type.
     * @param T    the [RequestParams] subtype.
     * @param params parameters forwarded to the request pipeline.
     * @return a [SharedFlow] with `replay = 1` emitting intercepted results.
     */
    inline fun <reified Need : Any, reified T : RequestParams> Request(
        params: T,
    ): SharedFlow<Need> = switchboard.handleRequest(Need::class, params)

    // ── Handler Overloads (launch + collect) ────────────────────────────

    /**
     * Subscribes to state values of type [O] from the state channel,
     * invoking [handler] each time a new value is emitted.
     *
     * Because state flows use `replay = 1`, the [handler] will be invoked
     * immediately with the latest value (if one has been broadcast).
     *
     * The [handler] runs with `this` [CoordinatorScope] as its receiver.
     *
     * ```kotlin
     * ListenFor<AppConfig> { config ->
     *     // react to config changes…
     * }
     * ```
     *
     * @param O       the state type to listen for (inferred).
     * @param handler invoked for each (intercepted) state value, with
     *                [CoordinatorScope] as the receiver.
     * @return the [Job] backing this subscription.
     */
    inline fun <reified O : Any> ListenFor(
        noinline handler: CoordinatorScope.(O) -> Unit,
    ): Job = launch {
        switchboard.stateFlow(O::class).collectLatest { handler(it) }
    }

    /**
     * Subscribes to [Impulse] events of type [A] from the reaction channel,
     * invoking [handler] for each event received.
     *
     * The [handler] runs with `this` [CoordinatorScope] as its receiver,
     * giving it direct access to [Broadcast], [Trigger], `launch`, and other
     * scope capabilities.
     *
     * The returned [Job] is a child of this scope's coroutine context and
     * will be canceled automatically on [dispose]. It can also be canceled
     * individually if needed.
     *
     * ```kotlin
     * val job = ReactTo<SessionExpired> {
     *     Trigger(NavigateToLogin)
     * }
     * ```
     *
     * @param A       the [Impulse] subtype to listen for (inferred).
     * @param handler invoked for each received impulse, with [CoordinatorScope]
     *                as the receiver.
     * @return the [Job] backing this subscription.
     */
    inline fun <reified A : Impulse> ReactTo(
        noinline handler: CoordinatorScope.(A) -> Unit,
    ): Job = launch {
        switchboard.impulseFlow(A::class).collect { handler(it) }
    }

    /**
     * Launches a request through the [SwitchBoard] and delivers each result
     * to [callback].
     *
     * The [callback] runs with `this` [CoordinatorScope] as its receiver,
     * so you can chain further broadcasts, triggers, or state updates
     * directly from the result handler.
     *
     * ```kotlin
     * Request<UserProfile, FetchUserParams>(FetchUserParams(userId = 42)) { profile ->
     *     launch { Broadcast(profile) }
     * }
     * ```
     *
     * @param Need     the expected result type from the request handler.
     * @param T        the [RequestParams] subtype describing the request.
     * @param params   parameters forwarded to the [SwitchBoard]'s request pipeline.
     * @param callback invoked with the result, with [CoordinatorScope] as the
     *                 receiver.
     * @return the [Job] backing this request.
     */
    inline fun <reified Need : Any, reified T : RequestParams> Request(
        params: T,
        noinline callback: CoordinatorScope.(Need) -> Unit,
    ): Job = launch {
        switchboard.handleRequest<Need, T>(params).collectLatest { need -> callback(need) }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Tears down this coordinator, releasing all resources.
     *
     * Specifically:
     * 1. **Cancels** the backing coroutine job and all of its children
     *    (listeners, in-flight requests, etc.).
     * 2. **Unregisters** every interceptor that was added via [Intercept].
     * 3. **Clears** the internal registration list.
     *
     * After calling [dispose], the scope is no longer usable — launching new
     * coroutines or registering interceptors will fail with a
     * [kotlinx.coroutines.CancellationException].
     *
     * This method is idempotent; calling it multiple times is safe.
     */
    fun dispose() {
        coroutineContext[Job]?.cancel()
        registrations.forEach { it.unregister() }
        registrations.clear()
    }
}