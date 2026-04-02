package com.synapselib.arch.base

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.mutableListOf

/**
 * Creates a lifecycle-aware [CoordinatorScope] and runs [block] with it as the
 * receiver, mirroring the [Node] DSL for non-Compose contexts.
 *
 * This is the primary entry point for wiring up [SwitchBoard] communication
 * outside a Compose composition — typically from an activity, fragment, service,
 * or background process.
 *
 * The coordinator automatically [disposes][CoordinatorScope.dispose] itself when
 * the [owner]'s lifecycle reaches [Lifecycle.Event.ON_DESTROY], so manual
 * cleanup is not required in most cases.
 *
 * @param switchboard the [SwitchBoard] this coordinator communicates through.
 * @param owner       the [LifecycleOwner] whose lifecycle governs this
 *                    coordinator's lifespan. The coordinator will auto-dispose
 *                    on [Lifecycle.Event.ON_DESTROY].
 * @param tag         optional human-readable label (e.g., `"AuthCoordinator"`)
 *                    attached to a [TraceContext] on every [CoordinatorScope.Trigger]
 *                    and [CoordinatorScope.Broadcast] call. When `null` (the default),
 *                    no tracing overhead is incurred.
 * @param block       initialization block — wire up listeners, interceptors, and
 *                    initial broadcasts here. Runs synchronously before the
 *                    function returns.
 * @return the fully initialized [CoordinatorScope]. Call
 *         [CoordinatorScope.dispose] to cancel all jobs and unregister all
 *         interceptors (also happens automatically on ON_DESTROY).
 */
fun Coordinator(
    switchboard: SwitchBoard,
    owner: LifecycleOwner,
    tag: String? = null,
    block: CoordinatorScope.() -> Unit,
): CoordinatorScope = CoordinatorScope(switchboard, owner, tag).apply(block)

/**
 * A long-lived, lifecycle-aware, non-Compose counterpart to [NodeScope].
 *
 * Provides the same [SwitchBoard] capabilities — broadcasting state,
 * triggering reactions, listening, requesting, and intercepting — with a
 * **lifecycle-managed lifespan** that auto-disposes on
 * [Lifecycle.Event.ON_DESTROY].
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
 * | **Lifecycle hooks** | — | [onCreate], [onStart], [onResume], [onPause], [onStop], [onDestroy] |
 *
 * ## CoroutineScope Delegation
 *
 * Implements [CoroutineScope] by delegation, so standard coroutine builders
 * (`launch`, `async`, etc.) are available directly on the scope and inside
 * handler receivers:
 *
 * ```kotlin
 * val coordinator = Coordinator(switchboard, viewLifecycleOwner) {
 *     ReactTo<DataRefreshRequested> {
 *         launch { Broadcast(fetchLatestData()) }
 *     }
 * }
 * ```
 *
 * ## Lifecycle
 *
 * The coordinator observes the provided [LifecycleOwner] and automatically
 * calls [dispose] when the lifecycle reaches [Lifecycle.Event.ON_DESTROY].
 * You may also call [dispose] manually at any time if earlier cleanup is
 * needed.
 *
 * ### Lifecycle Hooks
 *
 * Register callbacks for any [Lifecycle.Event] using the convenience
 * methods [onCreate], [onStart], [onResume], [onPause], [onStop], and
 * [onDestroy]. Callbacks are invoked in registration order when the
 * [LifecycleOwner] reaches the corresponding state:
 *
 * ```kotlin
 * val coordinator = Coordinator(switchboard, viewLifecycleOwner) {
 *     onStart { launch { Broadcast(SessionActive) } }
 *     onStop  { launch { Broadcast(SessionInactive) } }
 * }
 * ```
 *
 * [dispose] will:
 * 1. Cancel the backing coroutine job (and all child jobs/listeners).
 * 2. Unregister all interceptors added via [Intercept].
 * 3. Clear the internal registration and lifecycle-callback lists.
 * 4. Remove the lifecycle observer.
 *
 * @param switchboard    the [SwitchBoard] this scope communicates through.
 * @param owner          the [LifecycleOwner] governing this coordinator's lifespan.
 * @param tag            optional emitter label for [TraceContext]. When non-null, every
 *                       [Trigger] and [Broadcast] call wraps execution in a
 *                       `withContext(TraceContext(emitterTag = tag))`.
 */
class CoordinatorScope(
    val switchboard: SwitchBoard,
    private val owner: LifecycleOwner,
    val tag: String? = null,
) : CoroutineScope by owner.lifecycleScope {

    /**
     * Accumulated interceptor registrations that will be
     * [unregistered][Registration.unregister] on [dispose].
     */
    @PublishedApi
    internal val registrations = mutableListOf<Registration>()

    /**
     * Registered lifecycle-event callbacks, keyed by the [Lifecycle.Event] they
     * respond to. Callbacks are appended via [addLifecycleEventRegistration] and
     * invoked in registration order when the corresponding event fires.
     */
    @PublishedApi
    internal val lifecycleRegistrations = mutableMapOf<Lifecycle.Event, MutableList<() -> Unit>>()

    /**
     * Dispatches all callbacks registered for [event] via
     * [addLifecycleEventRegistration], in registration order.
     */
    private fun dispatchLifecycleCallbacks(event: Lifecycle.Event) {
        lifecycleRegistrations[event]?.forEach { it.invoke() }
    }

    /**
     * Lifecycle observer that dispatches registered callbacks for each
     * event and triggers [dispose] on [Lifecycle.Event.ON_DESTROY].
     */
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onCreate(owner: LifecycleOwner) {
            dispatchLifecycleCallbacks(Lifecycle.Event.ON_CREATE)
        }

        override fun onStart(owner: LifecycleOwner) {
            dispatchLifecycleCallbacks(Lifecycle.Event.ON_START)
        }

        override fun onResume(owner: LifecycleOwner) {
            dispatchLifecycleCallbacks(Lifecycle.Event.ON_RESUME)
        }

        override fun onPause(owner: LifecycleOwner) {
            dispatchLifecycleCallbacks(Lifecycle.Event.ON_PAUSE)
        }

        override fun onStop(owner: LifecycleOwner) {
            dispatchLifecycleCallbacks(Lifecycle.Event.ON_STOP)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            dispatchLifecycleCallbacks(Lifecycle.Event.ON_DESTROY)
            dispose()
        }
    }

    init {
        owner.lifecycle.addObserver(lifecycleObserver)
    }

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
     * @param point       the [InterceptPoint] (channel and direction) to intercept.
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
        if (tag != null) {
            withContext(TraceContext(emitterTag = tag)) {
                switchboard.broadcastState(data)
            }
        } else {
            switchboard.broadcastState(data)
        }
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
        if (tag != null) {
            withContext(TraceContext(emitterTag = tag)) {
                switchboard.triggerImpulse(event)
            }
        } else {
            switchboard.triggerImpulse(event)
        }
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
     * routing [impulse] through the [SwitchBoard]'s request pipeline.
     *
     * Use this overload when you need access to the raw flow.
     *
     * @param Need     the expected result type from the request handler.
     * @param I        the [Impulse] subtype describing the request.
     * @param impulse  parameters forwarded to the [SwitchBoard]'s request pipeline.
     * @return a [SharedFlow] with `replay = 1` emitting intercepted results.
     */
    inline fun <reified Need : Any, reified I : DataImpulse<Need>> Request(
        impulse: I,
    ): Flow<DataState<Need>> = switchboard.handleRequest(impulse)

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
     * @param Need     the expected result type from the request handler.
     * @param I        the [Impulse] subtype describing the request.
     * @param impulse  parameters forwarded to the [SwitchBoard]'s request pipeline.
     * @param callback invoked with the result, with [CoordinatorScope] as the
     *                 receiver.
     * @return the [Job] backing this request.
     */
    inline fun <reified Need : Any, reified I : DataImpulse<Need>> Request(
        impulse: I,
        noinline callback: CoordinatorScope.(DataState<Need>) -> Unit,
    ): Job = launch {
        switchboard.handleRequest(impulse).collectLatest { need -> callback(need) }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Appends a [callback] that will be invoked when the [LifecycleOwner]
     * reaches [event]. Multiple callbacks for the same event are executed
     * in the order they were registered.
     *
     * Prefer the convenience wrappers ([onCreate], [onStart], [onResume],
     * [onPause], [onStop], [onDestroy]) over calling this directly.
     *
     * @param event    the [Lifecycle.Event] to observe.
     * @param callback the action to run when the event fires.
     */
    @PublishedApi
    internal fun addLifecycleEventRegistration(event: Lifecycle.Event, callback: () -> Unit) {
        lifecycleRegistrations.computeIfAbsent(event) { mutableListOf() }.add(callback)
    }

    /**
     * Registers a [callback] to run when the [LifecycleOwner] reaches
     * [Lifecycle.Event.ON_CREATE].
     *
     * @param callback the action to execute on create.
     */
    fun onCreate(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_CREATE, callback)
    }

    /**
     * Registers a [callback] to run when the [LifecycleOwner] reaches
     * [Lifecycle.Event.ON_START].
     *
     * ```kotlin
     * Coordinator(switchboard, lifecycleOwner) {
     *     onStart { launch { Broadcast(SessionActive) } }
     * }
     * ```
     *
     * @param callback the action to execute on start.
     */
    fun onStart(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_START, callback)
    }

    /**
     * Registers a [callback] to run when the [LifecycleOwner] reaches
     * [Lifecycle.Event.ON_RESUME].
     *
     * @param callback the action to execute on resume.
     */
    fun onResume(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_RESUME, callback)
    }

    /**
     * Registers a [callback] to run when the [LifecycleOwner] reaches
     * [Lifecycle.Event.ON_PAUSE].
     *
     * @param callback the action to execute on pause.
     */
    fun onPause(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_PAUSE, callback)
    }

    /**
     * Registers a [callback] to run when the [LifecycleOwner] reaches
     * [Lifecycle.Event.ON_STOP]. Useful for releasing resources that
     * should only be held while the owner is visible.
     *
     * ```kotlin
     * Coordinator(switchboard, lifecycleOwner) {
     *     onStop { launch { Broadcast(SessionInactive) } }
     * }
     * ```
     *
     * @param callback the action to execute on stop.
     */
    fun onStop(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_STOP, callback)
    }

    /**
     * Registers a [callback] to run when the [LifecycleOwner] reaches
     * [Lifecycle.Event.ON_DESTROY].
     *
     * Note: The [CoordinatorScope] already auto-[disposes][dispose] on
     * ON_DESTROY. Use this for additional cleanup that goes beyond what
     * [dispose] handles (e.g., closing external resources).
     *
     * @param callback the action to execute on destroy.
     */
    fun onDestroy(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_DESTROY, callback)
    }

    /**
     * Tears down this coordinator, releasing all resources.
     *
     * Specifically:
     * 1. **Cancels** the backing coroutine job and all of its children
     *    (listeners, in-flight requests, etc.).
     * 2. **Unregisters** every interceptor that was added via [Intercept].
     * 3. **Clears** the internal registration list.
     * 4. **Removes** the lifecycle observer from the [LifecycleOwner].
     *
     * This is called automatically when the [LifecycleOwner] reaches
     * [Lifecycle.Event.ON_DESTROY]. You may also call it manually for
     * earlier cleanup.
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
        lifecycleRegistrations.clear()
        owner.lifecycle.removeObserver(lifecycleObserver)
    }
}