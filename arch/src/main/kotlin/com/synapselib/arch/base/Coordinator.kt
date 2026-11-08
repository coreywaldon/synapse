package com.synapselib.arch.base

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

// ── Builders ────────────────────────────────────────────────────────────

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
 * cleanup is not required in most cases. If [owner] is already
 * [Lifecycle.State.DESTROYED], the coordinator is disposed before [block] runs
 * (so everything [block] registers is inert).
 *
 * ### Lifecycle hooks registered in [block]
 *
 * The lifecycle observer is attached **after** [block] returns. Attaching
 * makes the owner replay the up-events it has already passed
 * (`ON_CREATE` → `ON_START` → `ON_RESUME`), so hooks registered in [block]
 * fire in lifecycle order even when the owner is already started or resumed
 * (the common case, e.g. `ProcessLifecycleOwner`):
 *
 * ```kotlin
 * Coordinator(switchboard, ProcessLifecycleOwner.get()) {
 *     onStart { launch { Broadcast(SessionActive) } } // fires right after the block
 * }
 * ```
 *
 * @param switchboard the [SwitchBoard] this coordinator communicates through.
 * @param owner       the [LifecycleOwner] whose lifecycle governs this
 *                    coordinator's lifespan. The coordinator will auto-dispose
 *                    on [Lifecycle.Event.ON_DESTROY].
 * @param tag         optional human-readable label (e.g., `"AuthCoordinator"`)
 *                    attached to a [TraceContext] on every [CoordinatorScope.Trigger],
 *                    [CoordinatorScope.Broadcast] and [CoordinatorScope.Request].
 *                    When `null` (the default), no tracing overhead is incurred.
 * @param block       initialization block — wire up listeners, interceptors,
 *                    lifecycle hooks, and initial broadcasts here. Runs
 *                    synchronously before the function returns. If it throws,
 *                    the coordinator is disposed and the exception rethrown.
 * @return the fully initialized [CoordinatorScope]. Call
 *         [CoordinatorScope.dispose] to cancel all jobs and unregister all
 *         interceptors (also happens automatically on ON_DESTROY).
 */
@Suppress("FunctionName")
fun Coordinator(
    switchboard: SwitchBoard,
    owner: LifecycleOwner,
    tag: String? = null,
    block: CoordinatorScope.() -> Unit,
): CoordinatorScope {
    val coordinator = CoordinatorScope(switchboard, owner, tag, attachLifecycleNow = false)
    coordinator.runBlock(block)
    coordinator.attachLifecycle()
    return coordinator
}

/**
 * Creates a **lifecycle-free** [CoordinatorScope] whose lifespan is bound to
 * [scope]'s [Job], and runs [block] with it as the receiver.
 *
 * Use this for app-wide or background coordinators that have no Android
 * lifecycle — e.g. bound to an application-level `CoroutineScope`, a service
 * scope, or another coordinator:
 *
 * ```kotlin
 * val sync = Coordinator(switchboard, applicationScope, tag = "SyncCoordinator") {
 *     ReactTo<SyncRequested> { launch { Broadcast(syncEngine.run()) } }
 *     onDispose { syncEngine.close() }
 * }
 * ```
 *
 * The coordinator's coroutines run in a [SupervisorJob] that is a child of
 * [scope]'s job (and use [scope]'s dispatcher). When [scope] is canceled, the
 * coordinator is [disposed][CoordinatorScope.dispose] automatically (via
 * `invokeOnCompletion`). Like any child job it keeps a *normally completing*
 * parent alive until it is disposed. If [scope] has no [Job] (e.g.
 * `GlobalScope`), the coordinator lives until [CoordinatorScope.dispose] is
 * called.
 *
 * Lifecycle hooks (`onStart`, `onStop`, …) are not available on a
 * lifecycle-free coordinator and throw [IllegalStateException]; use
 * [CoordinatorScope.onDispose] for teardown.
 *
 * @param switchboard the [SwitchBoard] this coordinator communicates through.
 * @param scope       the [CoroutineScope] whose job bounds the coordinator's lifespan.
 * @param tag         optional emitter label for [TraceContext]; see the
 *                    lifecycle-aware overload.
 * @param block       initialization block. If it throws, the coordinator is
 *                    disposed and the exception rethrown.
 * @return the initialized [CoordinatorScope].
 */
@Suppress("FunctionName")
fun Coordinator(
    switchboard: SwitchBoard,
    scope: CoroutineScope,
    tag: String? = null,
    block: CoordinatorScope.() -> Unit,
): CoordinatorScope = CoordinatorScope(switchboard, scope, tag).apply { runBlock(block) }

// ── CoordinatorScope ────────────────────────────────────────────────────

/**
 * A long-lived, non-Compose counterpart to [NodeScope].
 *
 * Provides the same [SwitchBoard] capabilities — broadcasting state,
 * triggering reactions, listening, requesting, and intercepting — with a
 * managed lifespan. A coordinator is bound either to a [LifecycleOwner]
 * (auto-dispose on [Lifecycle.Event.ON_DESTROY], lifecycle hooks available) or
 * to a plain [CoroutineScope] (auto-dispose when that scope's job completes,
 * no lifecycle hooks). Both kinds share this implementation; the lifecycle
 * binding is an optional attachment ([lifecycleOwner] is `null` for a
 * lifecycle-free coordinator).
 *
 * ## Capabilities
 *
 * Each consumption method has two overloads:
 * - **Flow overload** (inherited from [SynapseScope]): returns the flow
 *   directly for the caller to collect, combine, filter, or transform.
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
 * | **Request** | [Request]`(params)` → `Flow<DataState<Need>>` | [Request]`(params) { callback }` → [Job] |
 * | **Lifecycle hooks** | — | [onCreate], [onStart], [onResume], [onPause], [onStop], [onDestroy] |
 * | **Teardown** | — | [onDispose] |
 *
 * ## CoroutineScope Delegation
 *
 * Implements [CoroutineScope], so standard coroutine builders (`launch`,
 * `async`, etc.) are available directly on the scope and inside handler
 * receivers:
 *
 * ```kotlin
 * val coordinator = Coordinator(switchboard, viewLifecycleOwner) {
 *     ReactTo<DataRefreshRequested> {
 *         launch { Broadcast(fetchLatestData()) }
 *     }
 * }
 * ```
 *
 * ## Lifecycle hooks
 *
 * Register callbacks for lifecycle events with [onCreate], [onStart],
 * [onResume], [onPause], [onStop] and [onDestroy]. Callbacks for the same
 * event run in registration order every time the event is dispatched.
 *
 * Hooks behave the same no matter *when* they are registered:
 * - A hook for an **up-event** (`ON_CREATE`, `ON_START`, `ON_RESUME`) whose
 *   target state the owner has already reached is invoked **immediately**,
 *   synchronously on the registering thread — mirroring how
 *   `LifecycleRegistry` replays up-events to a new observer — and then again
 *   on every later occurrence of the event. Inside the [Coordinator] builder
 *   this replay happens right after the block, in lifecycle order.
 * - A hook for a **down-event** (`ON_PAUSE`, `ON_STOP`, `ON_DESTROY`) is never
 *   replayed; it fires the next time the event happens.
 * - Hooks registered after [dispose] are ignored.
 * - On a lifecycle-free coordinator ([lifecycleOwner] `== null`) the hooks
 *   throw [IllegalStateException] — there is no lifecycle to observe, and a
 *   silent no-op would hide the mistake. Use [onDispose] instead.
 *
 * ```kotlin
 * val coordinator = Coordinator(switchboard, viewLifecycleOwner) {
 *     onStart { launch { Broadcast(SessionActive) } }
 *     onStop  { launch { Broadcast(SessionInactive) } }
 * }
 * ```
 *
 * ## Disposal
 *
 * [dispose] will:
 * 1. Cancel the backing coroutine job (and all child jobs/listeners).
 * 2. Unregister all interceptors added via [Intercept].
 * 3. Clear the lifecycle-callback lists and remove the lifecycle observer.
 * 4. Run [onDispose] callbacks (once).
 *
 * ## Thread safety
 *
 * All registration methods ([Intercept], lifecycle hooks, [onDispose]) and
 * [dispose] may be called from any thread — e.g. from handler coroutines on
 * `Dispatchers.IO` while the main thread disposes. Anything registered after
 * (or concurrently with) disposal is cleaned up immediately and never leaks.
 * Note that `LifecycleRegistry` itself requires observers to be added on the
 * main thread, so construct lifecycle-bound coordinators there.
 *
 * @property switchboard the [SwitchBoard] this scope communicates through.
 * @property tag         optional emitter label for [TraceContext]. When non-null, every
 *                       [Trigger], [Broadcast] and [Request] runs inside a fresh
 *                       `TraceContext(emitterTag = tag)` linked to any ambient trace.
 * @property lifecycleOwner the [LifecycleOwner] this coordinator is bound to, or
 *                       `null` for a lifecycle-free coordinator.
 */
class CoordinatorScope private constructor(
    override val switchboard: SwitchBoard,
    parentContext: CoroutineContext,
    override val tag: String?,
    val lifecycleOwner: LifecycleOwner?,
    attachLifecycleNow: Boolean,
) : SynapseScope(), CoroutineScope {

    /**
     * Creates a coordinator bound to [owner]'s lifecycle. The lifecycle
     * observer is attached immediately; see the class docs for how hooks
     * registered afterwards behave. Prefer the [Coordinator] builder.
     *
     * @param switchboard the [SwitchBoard] this scope communicates through.
     * @param owner       the [LifecycleOwner] governing this coordinator's lifespan.
     * @param tag         optional emitter label for [TraceContext].
     */
    constructor(
        switchboard: SwitchBoard,
        owner: LifecycleOwner,
        tag: String? = null,
    ) : this(switchboard, owner.lifecycleScope.coroutineContext, tag, owner, attachLifecycleNow = true)

    /**
     * Creates a **lifecycle-free** coordinator whose lifespan is bound to
     * [scope]'s [Job]; it is disposed automatically when that job completes.
     * Prefer the [Coordinator] builder.
     *
     * @param switchboard the [SwitchBoard] this scope communicates through.
     * @param scope       the [CoroutineScope] bounding this coordinator.
     * @param tag         optional emitter label for [TraceContext].
     */
    constructor(
        switchboard: SwitchBoard,
        scope: CoroutineScope,
        tag: String? = null,
    ) : this(switchboard, scope.coroutineContext, tag, null, attachLifecycleNow = false)

    /** Used by the [Coordinator] builder to defer observer attachment until after its block. */
    internal constructor(
        switchboard: SwitchBoard,
        owner: LifecycleOwner,
        tag: String?,
        attachLifecycleNow: Boolean,
    ) : this(switchboard, owner.lifecycleScope.coroutineContext, tag, owner, attachLifecycleNow)

    /**
     * Isolated child job that scopes all coordinator coroutines. Using a
     * [SupervisorJob] parented to the owning scope's job means [dispose]
     * only cancels the coordinator's own work — not the parent scope — while
     * cancellation of the parent still cancels (and disposes) the coordinator.
     */
    private val job: CompletableJob = SupervisorJob(parentContext[Job])
    override val coroutineContext: CoroutineContext = parentContext + job

    /** Guards [disposeCallbacks] and the lifecycle binding's mutable state. */
    private val lock = Any()

    private val disposed = AtomicBoolean(false)

    private val tracker = RegistrationTracker()

    /** Callbacks registered via [onDispose]. Guarded by [lock]. */
    private val disposeCallbacks = ArrayList<() -> Unit>()

    private val lifecycleBinding: LifecycleBinding? = lifecycleOwner?.let { LifecycleBinding(it) }

    /**
     * Interceptor registrations made via [Intercept] that will be
     * [unregistered][Registration.unregister] on [dispose].
     *
     * **ABI-only.** `@PublishedApi` because the 1.0.x inline [Intercept] body —
     * already compiled into consumers' bytecode — calls
     * `getRegistrations().add(registration)`. The returned list is a view
     * whose `add` routes through the thread-safe tracker, so an `add` after
     * [dispose] unregisters the registration immediately instead of leaking
     * it; reads reflect the live tracked registrations. New code never needs
     * it: [Intercept] tracks registrations itself.
     */
    @PublishedApi
    internal val registrations: MutableList<Registration> get() = tracker.mutableView()

    /**
     * Snapshot of the registered lifecycle-event callbacks, keyed by event.
     * Empty for a lifecycle-free coordinator and after [dispose].
     *
     * **ABI-only** `@PublishedApi` (it was `@PublishedApi` in 1.0.x, so its
     * JVM getter `getLifecycleRegistrations()` is kept). The map and lists are
     * copies — mutating them does not register or remove hooks; use the
     * `onX { }` methods instead.
     */
    @PublishedApi
    internal val lifecycleRegistrations: Map<Lifecycle.Event, List<() -> Unit>>
        get() = synchronized(lock) {
            lifecycleBinding?.callbacks?.mapValues { it.value.toList() } ?: emptyMap()
        }

    /** `true` once [dispose] has been called (manually or automatically). */
    val isDisposed: Boolean get() = disposed.get()

    init {
        if (lifecycleOwner != null && lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            dispose()
        } else if (attachLifecycleNow) {
            attachLifecycle()
        }
        // Covers parent-scope cancellation (including a lifecycleScope that was
        // already canceled). Re-entrant calls into dispose() are no-ops.
        job.invokeOnCompletion { dispose() }
    }

    /** Runs a builder [block], disposing this coordinator if it throws. */
    internal fun runBlock(block: CoordinatorScope.() -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            dispose()
            throw t
        }
    }

    /**
     * Attaches the lifecycle observer (no-op for lifecycle-free or disposed
     * coordinators). The owner synchronously replays already-passed up-events,
     * dispatching any hooks registered so far in lifecycle order.
     */
    internal fun attachLifecycle() {
        val binding = lifecycleBinding ?: return
        if (disposed.get()) return
        binding.owner.lifecycle.addObserver(binding)
        if (binding.owner.lifecycle.currentState == Lifecycle.State.DESTROYED) dispose()
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
     * Thread-safe. If this coordinator is already disposed, the interceptor is
     * unregistered immediately (it never takes effect past this call) and the
     * returned handle is inert.
     *
     * @param T           the data type the interceptor operates on (inferred).
     * @param point       the [InterceptPoint] (channel and direction) to intercept.
     * @param interceptor the [Interceptor] to install.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     * @return a [Registration] handle; calling [Registration.unregister] removes
     *         the interceptor and stops tracking it.
     */
    @Suppress("FunctionName")
    inline fun <reified T : Any> Intercept(
        point: InterceptPoint,
        interceptor: Interceptor<T>,
        priority: Int = 0,
    ): Registration = trackRegistration(switchboard.addInterceptor(point, T::class, interceptor, priority))

    /** Tracks [registration] for disposal; see [RegistrationTracker.track]. */
    @PublishedApi
    internal fun trackRegistration(registration: Registration): Registration =
        tracker.track(registration)

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
    @Suppress("FunctionName")
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
     *     launch { Trigger(NavigateToLogin) }
     * }
     * ```
     *
     * @param A       the [Impulse] subtype to listen for (inferred).
     * @param handler invoked for each received impulse, with [CoordinatorScope]
     *                as the receiver.
     * @return the [Job] backing this subscription.
     */
    @Suppress("FunctionName")
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
    @Suppress("FunctionName")
    inline fun <reified Need : Any, reified I : DataImpulse<Need>> Request(
        impulse: I,
        noinline callback: CoordinatorScope.(DataState<Need>) -> Unit,
    ): Job = launch {
        traced(switchboard.handleRequest(I::class, Need::class, impulse))
            .collectLatest { need -> callback(need) }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Appends a [callback] that will be invoked when the [LifecycleOwner]
     * dispatches [event]. Multiple callbacks for the same event are executed
     * in the order they were registered. See the class docs for replay rules.
     *
     * `@PublishedApi` only to keep the 1.0.x JVM signature
     * `addLifecycleEventRegistration(Lifecycle.Event, Function0)`; prefer the
     * `onX { }` wrappers.
     *
     * @throws IllegalStateException on a lifecycle-free coordinator.
     */
    @PublishedApi
    internal fun addLifecycleEventRegistration(event: Lifecycle.Event, callback: () -> Unit) {
        require(event != Lifecycle.Event.ON_ANY) { "ON_ANY is not supported" }
        val binding = checkNotNull(lifecycleBinding) {
            "This CoordinatorScope is not bound to a LifecycleOwner, so lifecycle hooks " +
                "(${event.name}) can never fire. Use onDispose { } for teardown, or create the " +
                "coordinator with Coordinator(switchboard, lifecycleOwner) { }."
        }
        binding.register(event, callback)
    }

    /**
     * Registers a [callback] to run on [Lifecycle.Event.ON_CREATE]. Runs
     * immediately if the owner is already at least CREATED.
     *
     * @param callback the action to execute on create.
     * @throws IllegalStateException on a lifecycle-free coordinator.
     */
    fun onCreate(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_CREATE, callback)
    }

    /**
     * Registers a [callback] to run on [Lifecycle.Event.ON_START]. Runs
     * immediately if the owner is already at least STARTED.
     *
     * ```kotlin
     * Coordinator(switchboard, lifecycleOwner) {
     *     onStart { launch { Broadcast(SessionActive) } }
     * }
     * ```
     *
     * @param callback the action to execute on start.
     * @throws IllegalStateException on a lifecycle-free coordinator.
     */
    fun onStart(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_START, callback)
    }

    /**
     * Registers a [callback] to run on [Lifecycle.Event.ON_RESUME]. Runs
     * immediately if the owner is already RESUMED.
     *
     * @param callback the action to execute on resume.
     * @throws IllegalStateException on a lifecycle-free coordinator.
     */
    fun onResume(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_RESUME, callback)
    }

    /**
     * Registers a [callback] to run the next time the owner dispatches
     * [Lifecycle.Event.ON_PAUSE] (never replayed).
     *
     * @param callback the action to execute on pause.
     * @throws IllegalStateException on a lifecycle-free coordinator.
     */
    fun onPause(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_PAUSE, callback)
    }

    /**
     * Registers a [callback] to run the next time the owner dispatches
     * [Lifecycle.Event.ON_STOP] (never replayed). Useful for releasing
     * resources that should only be held while the owner is visible.
     *
     * ```kotlin
     * Coordinator(switchboard, lifecycleOwner) {
     *     onStop { launch { Broadcast(SessionInactive) } }
     * }
     * ```
     *
     * @param callback the action to execute on stop.
     * @throws IllegalStateException on a lifecycle-free coordinator.
     */
    fun onStop(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_STOP, callback)
    }

    /**
     * Registers a [callback] to run on [Lifecycle.Event.ON_DESTROY], just
     * before the coordinator auto-[dispose]s.
     *
     * Use this for additional cleanup that goes beyond what [dispose] handles
     * (e.g., closing external resources). For cleanup that should also run on
     * a manual [dispose] or on a lifecycle-free coordinator, use [onDispose].
     *
     * @param callback the action to execute on destroy.
     * @throws IllegalStateException on a lifecycle-free coordinator.
     */
    fun onDestroy(callback: () -> Unit) {
        addLifecycleEventRegistration(Lifecycle.Event.ON_DESTROY, callback)
    }

    /**
     * Registers a [callback] to run exactly once when this coordinator is
     * disposed — manually, on `ON_DESTROY`, or because its parent scope was
     * canceled. Works for both lifecycle-bound and lifecycle-free
     * coordinators. If the coordinator is already disposed, [callback] runs
     * immediately.
     *
     * Callbacks may run on the thread that triggered disposal (for scope
     * cancellation that is the job-completion thread), so keep them short and
     * non-throwing.
     *
     * @param callback the action to execute on dispose.
     */
    fun onDispose(callback: () -> Unit) {
        val runNow = synchronized(lock) {
            if (disposed.get()) true else { disposeCallbacks.add(callback); false }
        }
        if (runNow) callback()
    }

    /**
     * Tears down this coordinator, releasing all resources.
     *
     * Specifically:
     * 1. **Cancels** the backing coroutine job and all of its children
     *    (listeners, in-flight requests, etc.).
     * 2. **Unregisters** every interceptor that was added via [Intercept].
     * 3. **Clears** the lifecycle-callback lists and **removes** the lifecycle
     *    observer from the [LifecycleOwner].
     * 4. **Runs** [onDispose] callbacks (first call only).
     *
     * This is called automatically when the [LifecycleOwner] reaches
     * [Lifecycle.Event.ON_DESTROY], or when the parent scope of a
     * lifecycle-free coordinator is canceled. You may also call it manually
     * for earlier cleanup.
     *
     * After calling [dispose], [isDisposed] is `true` and the backing [Job] is
     * canceled. Coroutines started via [launch] / [kotlinx.coroutines.async]
     * return immediately in a canceled state. [Intercept] after dispose
     * unregisters the new interceptor immediately, and lifecycle hooks
     * registered after dispose are ignored — nothing leaks.
     *
     * Idempotent and thread-safe. If called off the main thread, removing the
     * lifecycle observer may be refused by `LifecycleRegistry`; the observer is
     * then detached lazily on its next event (it is inert in the meantime).
     */
    fun dispose() {
        val first = disposed.compareAndSet(false, true)
        job.cancel()
        tracker.dispose()
        val callbacks = synchronized(lock) {
            lifecycleBinding?.callbacks?.clear()
            if (first) disposeCallbacks.toList().also { disposeCallbacks.clear() } else emptyList()
        }
        if (first) lifecycleBinding?.detach()
        callbacks.forEach { it() }
    }

    // ── Lifecycle binding ───────────────────────────────────────────────

    /**
     * Optional lifecycle attachment: observes [owner], tracks the state it has
     * actually dispatched to us, and runs the registered hooks.
     *
     * [observedState] (rather than `owner.lifecycle.currentState`) decides
     * whether a newly registered up-event hook is replayed, so a hook is never
     * invoked twice for the same event even when registered during dispatch.
     */
    private inner class LifecycleBinding(val owner: LifecycleOwner) : LifecycleEventObserver {

        /** The state implied by the last event delivered to us. Guarded by [lock]. */
        var observedState: Lifecycle.State = Lifecycle.State.INITIALIZED

        /** Hooks by event, in registration order. Guarded by [lock]. */
        val callbacks: MutableMap<Lifecycle.Event, MutableList<() -> Unit>> =
            EnumMap(Lifecycle.Event::class.java)

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            if (event == Lifecycle.Event.ON_ANY) return
            val toRun: List<() -> Unit>? = synchronized(lock) {
                if (disposed.get()) {
                    null
                } else {
                    observedState = event.targetState
                    callbacks[event]?.toList().orEmpty()
                }
            }
            if (toRun == null) {
                // Disposed off the main thread earlier; finish detaching now.
                detach()
                return
            }
            toRun.forEach { it() }
            if (event == Lifecycle.Event.ON_DESTROY) dispose()
        }

        fun register(event: Lifecycle.Event, callback: () -> Unit) {
            val runNow = synchronized(lock) {
                if (disposed.get()) return
                callbacks.getOrPut(event) { ArrayList() }.add(callback)
                event.isUpEvent() && observedState.isAtLeast(event.targetState)
            }
            if (runNow) callback()
        }

        fun detach() {
            try {
                owner.lifecycle.removeObserver(this)
            } catch (_: IllegalStateException) {
                // LifecycleRegistry refuses off-main-thread removal; the observer
                // is inert once disposed and removes itself on its next event.
            }
        }

        private fun Lifecycle.Event.isUpEvent(): Boolean =
            this == Lifecycle.Event.ON_CREATE ||
                this == Lifecycle.Event.ON_START ||
                this == Lifecycle.Event.ON_RESUME
    }
}
