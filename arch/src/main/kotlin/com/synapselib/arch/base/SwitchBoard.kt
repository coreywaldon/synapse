package com.synapselib.arch.base

import androidx.compose.runtime.compositionLocalOf
import com.synapselib.arch.base.internal.ChannelStore
import com.synapselib.arch.base.internal.DownstreamStage
import com.synapselib.arch.base.provider.ProviderManager
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.core.typed.DataState
import com.synapselib.core.typed.TypedSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.inject.Inject
import javax.inject.Qualifier
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The direction of data flow relative to the [SwitchBoard].
 *
 * - [UPSTREAM]: data is flowing **into** the board (e.g., a broadcast or trigger
 *   before it reaches the underlying flow/router).
 * - [DOWNSTREAM]: data is flowing **out of** the board (e.g., a value being
 *   delivered to a listener or routed to a handler).
 */
enum class Direction { UPSTREAM, DOWNSTREAM }

/**
 * Identifies the communication channel that data travels through.
 *
 * - [REQUEST]: one-shot request/response interactions routed via the [ProviderManager].
 * - [STATE]: persistent, replay-1 state streams (the latest value is always cached).
 * - [REACTION]: fire-and-forget event streams with no replay.
 */
enum class Channel { REQUEST, STATE, REACTION }

/**
 * A coordinate that uniquely identifies **where** in the [SwitchBoard] an
 * interceptor should be installed.
 *
 * Combining a [Channel] with a [Direction] yields six possible intercept
 * points, for example:
 *
 * | Point | Meaning |
 * |---|---|
 * | `(REQUEST, UPSTREAM)` | Before request params reach the router |
 * | `(REQUEST, DOWNSTREAM)` | After routing, before the result is emitted to the flow |
 * | `(STATE, UPSTREAM)` | Before a state broadcast is emitted to the flow |
 * | `(STATE, DOWNSTREAM)` | Before a collected state value is delivered to the listener |
 * | `(REACTION, UPSTREAM)` | Before a reaction is emitted to the flow |
 * | `(REACTION, DOWNSTREAM)` | Before a collected reaction is delivered to the listener |
 *
 * Prefer the pre-allocated constants ([STATE_UPSTREAM], …) or [of] over the
 * constructor to avoid allocating on hot paths.
 *
 * @property channel the communication channel ([Channel.REQUEST], [Channel.STATE], or [Channel.REACTION]).
 * @property direction whether the interception occurs on the producing
 *                     ([Direction.UPSTREAM]) or consuming ([Direction.DOWNSTREAM]) side.
 */
data class InterceptPoint(val channel: Channel, val direction: Direction) {

    /** Dense index in `0 until 6`, used for array-backed per-point storage. */
    internal val index: Int
        get() = channel.ordinal * Direction.entries.size + direction.ordinal

    /**
     * Pre-allocated constants for all six possible intercept points.
     *
     * Since there are only 6 combinations of [Channel] × [Direction], reusing
     * these avoids allocating a new data class instance on every broadcast,
     * trigger, or request.
     */
    companion object {
        val STATE_UPSTREAM = InterceptPoint(Channel.STATE, Direction.UPSTREAM)
        val STATE_DOWNSTREAM = InterceptPoint(Channel.STATE, Direction.DOWNSTREAM)
        val REACTION_UPSTREAM = InterceptPoint(Channel.REACTION, Direction.UPSTREAM)
        val REACTION_DOWNSTREAM = InterceptPoint(Channel.REACTION, Direction.DOWNSTREAM)
        val REQUEST_UPSTREAM = InterceptPoint(Channel.REQUEST, Direction.UPSTREAM)
        val REQUEST_DOWNSTREAM = InterceptPoint(Channel.REQUEST, Direction.DOWNSTREAM)

        /** Number of distinct intercept points. */
        internal const val COUNT: Int = 6

        /** All six constants, ordered by [index] (channel-major). */
        private val BY_INDEX: Array<InterceptPoint> = arrayOf(
            REQUEST_UPSTREAM, REQUEST_DOWNSTREAM,
            STATE_UPSTREAM, STATE_DOWNSTREAM,
            REACTION_UPSTREAM, REACTION_DOWNSTREAM,
        )

        /** All six intercept points (the pre-allocated constants), channel-major. */
        val ALL: List<InterceptPoint> = BY_INDEX.asList()

        /**
         * Returns the pre-allocated constant for ([channel], [direction]).
         * Never allocates.
         */
        fun of(channel: Channel, direction: Direction): InterceptPoint =
            BY_INDEX[channel.ordinal * Direction.entries.size + direction.ordinal]
    }
}

/**
 * Central message bus that coordinates **requests**, **state**, and **reactions**
 * across an application, with built-in support for interceptors at every stage.
 *
 * ## Three Channels
 *
 * | Channel | Semantics | Replay | Typical use |
 * |---|---|---|---|
 * | **Request** | Routed command → cold `Flow` of `DataState` results | latest state, via the provider | Network calls, navigation |
 * | **State** | Latest-value stream (`replay = 1`) | 1 | UI state, preferences |
 * | **Reaction/Impulse** | Fire-and-forget event stream | 0 | Toasts, analytics pings |
 *
 * ## Flow-First Consumption
 *
 * The state and reaction channels expose a [SharedFlow] with downstream
 * interception already applied; requests return a cold [Flow] whose results
 * are downstream-intercepted. Callers collect, combine, filter, or transform
 * these flows however they like:
 *
 * ```kotlin
 * // Direct collection
 * switchBoard.stateFlow<UserProfile>().collectLatest { ... }
 *
 * // Composition
 * combine(
 *     switchBoard.stateFlow<UserProfile>(),
 *     switchBoard.stateFlow<ThemeSettings>(),
 * ) { profile, theme -> ... }
 * ```
 *
 * Raw (unintercepted) flows are available via [getRawStateFlow] and
 * [getRawImpulseFlow] for advanced use cases.
 *
 * ## Interception
 *
 * Every channel supports **upstream** (producer-side) and **downstream**
 * (consumer-side) interceptors. Register them via [addInterceptor] with an
 * [InterceptPoint] that identifies the exact stage.
 *
 * ## Observation
 *
 * Type-agnostic, read-only observers (logging, metrics, tracing) are
 * registered via [addObserver]; see [SwitchBoardObserver].
 *
 * ## Implementing this interface
 *
 * Members added to this interface after its first release come with default
 * implementations, so third-party implementations and test fakes keep
 * compiling. Optional capabilities (such as [addObserver]) throw
 * [UnsupportedOperationException] by default.
 *
 * ## Thread Safety
 *
 * All members are safe to call from any coroutine or thread. The underlying
 * flows and interceptor registries use concurrent data structures.
 *
 * @see DefaultSwitchBoard for the default implementation.
 * @see InterceptorRegistry for how interceptors are stored and executed.
 */
interface SwitchBoard {

    // ── Produce (Upstream) ──────────────────────────────────────────────

    /**
     * Emits a new [data] value into the state channel for type [clazz].
     *
     * Upstream interceptors (`STATE / UPSTREAM`) are applied before the value
     * is emitted. Because state flows use `replay = 1`, the most recent value
     * is always cached and immediately delivered to new collectors.
     *
     * @param T     the state type.
     * @param clazz the [KClass] token identifying the state stream.
     * @param data  the new state value to broadcast.
     */
    suspend fun <T : Any> broadcastState(clazz: KClass<T>, data: T)

    /**
     * Emits a fire-and-forget reaction into the reaction channel for type [clazz].
     *
     * Upstream interceptors (`REACTION / UPSTREAM`) are applied before the
     * value is emitted. Reaction flows have **no replay**, so only active
     * collectors will receive the event.
     *
     * @param T     the reaction type.
     * @param clazz the [KClass] token identifying the reaction stream.
     * @param data  the reaction event to emit.
     */
    suspend fun <T : Any> triggerImpulse(clazz: KClass<T>, data: T)

    /**
     * Routes a request through the interceptor pipeline and returns a cold
     * [Flow] of [DataState] results.
     *
     * 1. **Upstream interceptors** (`REQUEST / UPSTREAM`) are applied to the [impulse].
     * 2. The (possibly transformed) params are forwarded to the [com.synapselib.arch.base.provider.ProviderManager].
     * 3. **Downstream interceptors** (`REQUEST / DOWNSTREAM`) are applied to
     *    each `Success`/`Error` payload before it is emitted.
     *
     * The returned flow is cold — each subscriber re-runs upstream interception
     * and subscribes to the provider pipeline. The provider itself deduplicates
     * in-flight work by impulse equality and retains the most recent
     * [DataState] via an internal `replay = 1` buffer, so a late subscriber
     * that arrives before the underlying job completes still observes the
     * latest state.
     *
     * @param Need        the type of the result expected by the caller.
     * @param I           the concrete [DataImpulse] subtype.
     * @param impulseType the [KClass] token for [DataImpulse]
     * @param needType    the [KClass] token for [Need], used for type-safe
     *                    downstream interception.
     * @return a cold [Flow] that emits intercepted [DataState] values.
     */
    fun <Need : Any, I : DataImpulse<Need>> handleRequest(
        impulseType: KClass<I>,
        needType: KClass<Need>,
        impulse: I,
    ): Flow<DataState<Need>>

    // ── Consume (Downstream-intercepted) ────────────────────────────────

    /**
     * Returns a [SharedFlow] for the state channel of type [clazz], with
     * downstream interceptors (`STATE / DOWNSTREAM`) already applied.
     *
     * The flow has `replay = 1`, so new collectors immediately receive the
     * latest intercepted value (if one has been broadcast).
     *
     * @param T     the state type.
     * @param clazz the [KClass] token identifying the state stream.
     * @return a [SharedFlow] emitting intercepted state values.
     */
    fun <T : Any> stateFlow(clazz: KClass<T>): SharedFlow<T>

    /**
     * Returns a [SharedFlow] for the reaction channel of type [clazz], with
     * downstream interceptors (`REACTION / DOWNSTREAM`) already applied.
     *
     * The flow has `replay = 0` — only active collectors receive events.
     *
     * @param T     the reaction type.
     * @param clazz the [KClass] token identifying the reaction stream.
     * @return a [SharedFlow] emitting intercepted reaction events.
     */
    fun <T : Any> impulseFlow(clazz: KClass<T>): SharedFlow<T>

    // ── Raw Flow Access ─────────────────────────────────────────────────

    /**
     * Returns the read-only [SharedFlow] backing the state channel for [clazz],
     * **without** downstream interception applied.
     *
     * Useful for advanced composition (e.g., combining multiple state flows)
     * where the caller will manage interception themselves.
     *
     * @param clazz the [KClass] token identifying the state stream.
     * @return a [SharedFlow] that emits values broadcast via [broadcastState].
     */
    fun getRawStateFlow(clazz: KClass<*>): SharedFlow<Any>

    /**
     * Returns the read-only [SharedFlow] backing the reaction channel for
     * [clazz], **without** downstream interception applied.
     *
     * @param clazz the [KClass] token identifying the reaction stream.
     * @return a [SharedFlow] that emits values triggered via [triggerImpulse].
     */
    fun getRawImpulseFlow(clazz: KClass<*>): SharedFlow<Any>

    // ── Interception ────────────────────────────────────────────────────

    /**
     * Registers an [interceptor] at the specified [point] for data of type [clazz].
     *
     * @param T           the data type the interceptor operates on.
     * @param point       identifies the channel and direction to intercept.
     * @param clazz       the [KClass] token for [T].
     * @param interceptor the interceptor to install.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     * @return a [Registration] handle — call [Registration.unregister] to remove
     *         the interceptor from future executions.
     */
    fun <T : Any> addInterceptor(
        point: InterceptPoint,
        clazz: KClass<T>,
        interceptor: Interceptor<T>,
        priority: Int = 0,
    ): Registration

    // ── Observation ─────────────────────────────────────────────────────

    /**
     * Registers a type-agnostic, read-only [observer] that is notified of every
     * value at every [InterceptPoint] (after that point's interceptors ran),
     * together with the active [TraceContext], if any.
     *
     * Any number of observers may be registered; they are invoked in
     * registration order. See [SwitchBoardObserver] for the full contract.
     *
     * The default implementation throws [UnsupportedOperationException], so
     * that existing third-party implementations of this interface keep
     * compiling; [DefaultSwitchBoard] supports it.
     *
     * @param observer the observer to add.
     * @return a [Registration] that removes the observer. Unregistering is idempotent.
     * @throws UnsupportedOperationException if this implementation does not
     *         support observers.
     */
    fun addObserver(observer: SwitchBoardObserver): Registration =
        throw UnsupportedOperationException(
            "${this::class.qualifiedName} does not support SwitchBoard observers",
        )
}

// ── Reified Extension Functions ─────────────────────────────────────────


/** Reified convenience overload for [SwitchBoard.broadcastState]. */
suspend inline fun <reified T : Any> SwitchBoard.broadcastState(data: T) =
    broadcastState(T::class, data)

/** Reified convenience overload for [SwitchBoard.triggerImpulse]. */
suspend inline fun <reified T : Any> SwitchBoard.triggerImpulse(data: T) =
    triggerImpulse(T::class, data)

/** Reified convenience overload for [SwitchBoard.handleRequest]. */
inline fun <reified Need : Any, reified I : DataImpulse<Need>> SwitchBoard.handleRequest(
    impulse: I,
): Flow<DataState<Need>> = handleRequest(I::class, Need::class, impulse)

/** Reified convenience overload for [SwitchBoard.addInterceptor]. */
inline fun <reified T : Any> SwitchBoard.addInterceptor(
    point: InterceptPoint,
    interceptor: Interceptor<T>,
    priority: Int = 0,
): Registration = addInterceptor(point, T::class, interceptor, priority)

/**
 * Adds logging interceptors for all combinations of [Channel] and [Direction].
 * The provided logging function [log] is invoked for each data instance of type [T]
 * as it passes through the specified intercept points in the system.
 *
 * Upstream loggers run first (`Int.MIN_VALUE` priority) and downstream loggers
 * run last (`Int.MAX_VALUE`), so they see values as produced and as delivered.
 * To observe *all* types without registering per-type interceptors, prefer
 * [SwitchBoard.addObserver].
 *
 * @param T The data type for which the logging interceptors are applied.
 * @param log A function that performs logging or side effects for each observed data instance of type [T].
 * @return a single [Registration] that removes all six interceptors.
 */
inline fun <reified T: Any> SwitchBoard.addLoggingInterceptors(crossinline log: (T) -> Unit): Registration {
    val interceptor = Interceptor.read<T> { data -> log.invoke(data) }
    val registrations = ArrayList<Registration>(InterceptPoint.ALL.size)
    for (point in InterceptPoint.ALL) {
        registrations += addInterceptor(
            point       = point,
            interceptor = interceptor,
            priority    = if (point.direction == Direction.UPSTREAM) Int.MIN_VALUE else Int.MAX_VALUE,
        )
    }
    return Registration { registrations.forEach { it.unregister() } }
}

@Qualifier
annotation class SwitchBoardStopTimeout
@Qualifier
annotation class SwitchBoardReplayExpiration
@Qualifier
annotation class SwitchBoardScope
@Qualifier
annotation class SwitchBoardWorkerContext

// ── Implementation ──────────────────────────────────────────────────────

/**
 * Default [SwitchBoard] implementation backed by Kotlin [SharedFlow]s and
 * a [com.synapselib.arch.base.provider.ProviderManager] for request dispatch.
 *
 * ## Construction
 *
 * ```kotlin
 * // Defaults (see SwitchBoardConfig)
 * val board = DefaultSwitchBoard(scope, registry)
 *
 * // Configured
 * val board = DefaultSwitchBoard(scope, registry, SwitchBoardConfig {
 *     stopTimeout = 5.seconds
 *     maxConcurrentJobs = 4
 * })
 * ```
 *
 * A secondary `@Inject` constructor taking the qualified loose parameters
 * ([SwitchBoardScope], [SwitchBoardWorkerContext], [SwitchBoardStopTimeout],
 * [SwitchBoardReplayExpiration]) is kept for dependency injection and source
 * compatibility.
 *
 * ## Internal Architecture
 *
 * ```
 *                     ┌──────────────────────────────────┐
 *                     │       DefaultSwitchBoard         │
 *                     │                                  │
 *  broadcastState ──▶ │  upstream interceptors → Lane    │ ──▶ stateFlow (downstream intercepted)
 *  triggerImpulse ──▶ │  upstream interceptors → Lane    │ ──▶ impulseFlow (downstream intercepted)
 *                     │                                  │
 *  request ─────────▶ │  ProviderManager                 │
 *                     │    ├─ dedup by impulse equality  │
 *                     │    ├─ factory → Provider instance│
 *                     │    ├─ ProviderScope (SB access)  │
 *                     │    └─ DataState lifecycle        │ ──▶ Flow<DataState<T>>
 *                     └──────────────────────────────────┘
 * ```
 *
 * - **State channels**: one lane per type, `MutableSharedFlow(replay = 1)` —
 *   the latest value is always cached and delivered immediately to new collectors.
 * - **Reaction channels**: one lane per type, `MutableSharedFlow(replay = 0)`
 *   with [SwitchBoardConfig.reactionBufferCapacity] extra buffer and
 *   [SwitchBoardConfig.reactionOverflow] — fire-and-forget events with a
 *   buffer to absorb bursts.
 * - **Downstream flows**: each lane lazily creates exactly one
 *   downstream-intercepted [SharedFlow], shared by all consumers of that type
 *   and started with [SwitchBoardConfig.sharingStarted].
 * - **Interceptors**: one [InterceptorRegistry] per [InterceptPoint], allowing
 *   independent priority ordering for each stage.
 *
 * ## Downstream failures
 *
 * If a downstream interceptor or observer throws while a state/reaction value
 * is being delivered, that single value is dropped (listeners keep their
 * previous value), [SwitchBoardObserver.onDeliveryFailure] is invoked, and the
 * exception is reported to the scope's
 * [kotlinx.coroutines.CoroutineExceptionHandler] (or the platform default
 * handler). The channel itself stays alive for subsequent values.
 *
 * ## Lifecycle
 *
 * The board runs its sharing coroutines and provider jobs under its **own**
 * [SupervisorJob], a child of the `scope` it was given. Calling [close]
 * cancels that job — and therefore everything the board launched — without
 * cancelling the caller's scope. Cancelling the caller's scope also stops the
 * board's coroutines (but does not mark the board [closed][isClosed]).
 *
 * Note that, because the board's job is a child of `scope`'s job, a scope that
 * waits for its children to complete (e.g. a `TestScope` passed directly to
 * `runTest`) waits until the board is closed or the scope is cancelled.
 *
 * ## Thread Safety
 *
 * All internal maps are concurrent and all flows are thread-safe.
 * Interceptor/observer registration and pipeline execution may happen
 * concurrently.
 *
 * @param scope            the parent scope. The board creates a child
 *                         [SupervisorJob] of it for everything it launches.
 * @param providerRegistry the providers available to [handleRequest].
 * @property config        the immutable configuration of this board.
 */
class DefaultSwitchBoard(
    scope: CoroutineScope,
    providerRegistry: ProviderRegistry,
    val config: SwitchBoardConfig = SwitchBoardConfig.DEFAULT,
) : SwitchBoard, AutoCloseable {

    /**
     * Dependency-injection / legacy constructor taking loose, qualified
     * parameters. Equivalent to the primary constructor with a
     * [SwitchBoardConfig] built from these values (all other options at
     * their defaults).
     *
     * @param scope                  parent scope ([SwitchBoardScope]).
     * @param providerRegistry       the providers available to [handleRequest].
     * @param workerContext          context provider jobs run in ([SwitchBoardWorkerContext]).
     * @param stopTimeoutMillis      see [SwitchBoardConfig.stopTimeout] ([SwitchBoardStopTimeout]).
     * @param replayExpirationMillis see [SwitchBoardConfig.replayExpiration] ([SwitchBoardReplayExpiration]).
     */
    @Inject
    constructor(
        @SwitchBoardScope scope: CoroutineScope,
        providerRegistry: ProviderRegistry,
        @SwitchBoardWorkerContext workerContext: CoroutineContext = Dispatchers.IO,
        @SwitchBoardStopTimeout stopTimeoutMillis: Long = 3.seconds.inWholeMilliseconds,
        @SwitchBoardReplayExpiration replayExpirationMillis: Long = 3.seconds.inWholeMilliseconds,
    ) : this(
        scope,
        providerRegistry,
        SwitchBoardConfig {
            this.workerContext = workerContext
            this.stopTimeout = stopTimeoutMillis.milliseconds
            this.replayExpiration = replayExpirationMillis.milliseconds
        },
    )

    // ── Lifecycle state ─────────────────────────────────────────────────

    /** The board's own job: a supervisor child of the caller's scope. */
    private val boardJob: Job = SupervisorJob(scope.coroutineContext[Job])

    /** Scope for everything the board launches (sharing coroutines, providers). */
    private val boardScope: CoroutineScope = CoroutineScope(scope.coroutineContext + boardJob)

    private val closed = AtomicBoolean(false)

    /** `true` once [close] has been called. */
    val isClosed: Boolean get() = closed.get()

    /** Provider lifecycle manager — handles cold-start, dedup, concurrency. */
    private val providerManager =
        ProviderManager(
            providerRegistry,
            boardScope,
            config.workerContext,
            config.maxConcurrentJobs,
            idleTimeoutMillis = config.providerIdleTimeout.let {
                if (it.isInfinite()) Long.MAX_VALUE else it.inWholeMilliseconds
            },
        )

    // ── Sharing strategy ────────────────────────────────────────────────

    /**
     * Current sharing strategy. Only differs from [SwitchBoardConfig.sharingStarted]
     * if the deprecated [setEagerSharing] was called.
     */
    @Volatile
    private var sharingStarted: SharingStarted = config.sharingStarted

    /** Set once the first downstream flow has captured [sharingStarted]. */
    private val sharingFrozen = AtomicBoolean(false)

    private fun captureSharingStarted(): SharingStarted {
        sharingFrozen.set(true)
        return sharingStarted
    }

    /**
     * Switches downstream flows to [SharingStarted.Eagerly].
     *
     * The strategy is captured when a type's downstream flow is first created
     * (the first [stateFlow]/[impulseFlow] call for that type), so this only
     * affects flows created afterwards. Calling it after any downstream flow
     * already exists therefore throws instead of silently half-applying.
     *
     * @throws IllegalStateException if a downstream flow was already created.
     *         The strategy is still switched for flows created later.
     */
    @Deprecated(
        "Racy and order-dependent. Configure the strategy up front with " +
            "DefaultSwitchBoard(scope, registry, SwitchBoardConfig { eagerSharing() }).",
    )
    fun setEagerSharing() {
        sharingStarted = SharingStarted.Eagerly
        check(!sharingFrozen.get()) {
            "setEagerSharing() was called after the first stateFlow()/impulseFlow() call; " +
                "flows created before this call keep their previous sharing strategy. " +
                "Use SwitchBoardConfig { eagerSharing() } instead."
        }
    }

    // ── Channels ────────────────────────────────────────────────────────

    /** Downstream hook shared by both channel stores. */
    private val downstreamStage = DownstreamStage { point, clazz, value ->
        try {
            processAndLog(point, clazz, value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            reportDeliveryFailure(point, clazz, value, e)
            DownstreamStage.DROP
        }
    }

    /** Per-type state lanes: `replay = 1`, latest value wins. */
    private val stateStore = ChannelStore(
        downstreamPoint = InterceptPoint.STATE_DOWNSTREAM,
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        scope = boardScope,
        started = ::captureSharingStarted,
        stage = downstreamStage,
    )

    /** Per-type reaction lanes: no replay, configurable burst buffer. */
    private val reactionStore = ChannelStore(
        downstreamPoint = InterceptPoint.REACTION_DOWNSTREAM,
        replay = 0,
        extraBufferCapacity = config.reactionBufferCapacity,
        onBufferOverflow = config.reactionOverflow,
        scope = boardScope,
        started = ::captureSharingStarted,
        stage = downstreamStage,
    )

    // ── Interceptor registries ──────────────────────────────────────────

    /**
     * One lazily created [InterceptorRegistry] per [InterceptPoint], indexed
     * by [InterceptPoint.index]. Interceptors registered for e.g.
     * `(STATE, UPSTREAM)` never interfere with those for `(STATE, DOWNSTREAM)`.
     */
    private val registries = AtomicReferenceArray<InterceptorRegistry?>(InterceptPoint.COUNT)

    private fun registryFor(point: InterceptPoint): InterceptorRegistry {
        val index = point.index
        registries.get(index)?.let { return it }
        val created = InterceptorRegistry()
        return if (registries.compareAndSet(index, null, created)) created else registries.get(index)!!
    }

    private fun pipelineFor(point: InterceptPoint): InterceptorPipeline =
        registries.get(point.index) ?: EmptyPipeline

    // ── Observers ───────────────────────────────────────────────────────

    /** Guards [observerHandles], [legacyLogger], [legacyTraceListener]. */
    private val observerLock = Any()
    private val observerHandles = ArrayList<ObserverHandle>()
    private var legacyLogger: SwitchBoardObserver? = null
    private var legacyTraceListener: SwitchBoardObserver? = null

    /**
     * Immutable, copy-on-write array read on the hot path. Order: legacy
     * global logger, legacy trace listener, then [addObserver] registrations
     * in registration order. Empty when nothing observes the board.
     */
    @Volatile
    private var observerSnapshot: Array<SwitchBoardObserver> = NO_OBSERVERS

    private inner class ObserverHandle(val observer: SwitchBoardObserver) : Registration {
        override fun unregister() {
            synchronized(observerLock) {
                if (observerHandles.remove(this)) publishObserversLocked()
            }
        }
    }

    private fun publishObserversLocked() {
        val list = ArrayList<SwitchBoardObserver>(observerHandles.size + 2)
        legacyLogger?.let(list::add)
        legacyTraceListener?.let(list::add)
        observerHandles.mapTo(list) { it.observer }
        observerSnapshot = if (list.isEmpty()) NO_OBSERVERS else list.toTypedArray()
    }

    // ── Produce (Upstream) ──────────────────────────────────────────────

    override suspend fun <T : Any> broadcastState(clazz: KClass<T>, data: T) {
        checkOpen()
        val processed = processAndLog(InterceptPoint.STATE_UPSTREAM, clazz, data)
        stateStore.lane(clazz).raw.emit(processed)
    }

    override suspend fun <T : Any> triggerImpulse(clazz: KClass<T>, data: T) {
        checkOpen()
        val processed = processAndLog(InterceptPoint.REACTION_UPSTREAM, clazz, data)
        reactionStore.lane(clazz).raw.emit(processed)
    }

    /**
     * See [SwitchBoard.handleRequest].
     *
     * @throws IllegalStateException if the board is closed — both when called
     *         and when the returned flow is collected.
     */
    override fun <Need : Any, I : DataImpulse<Need>> handleRequest(
        impulseType: KClass<I>,
        needType: KClass<Need>,
        impulse: I,
    ): Flow<DataState<Need>> {
        checkOpen()
        return flow {
            checkOpen()
            val processedImpulse = processAndLog(InterceptPoint.REQUEST_UPSTREAM, impulseType, impulse)

            val providerFlow = try {
                providerManager.request(
                    impulseType,
                    needType,
                    processedImpulse,
                    switchboard = this@DefaultSwitchBoard
                )
            } catch (e: Exception) {
                emit(DataState.Error(e))
                return@flow
            }

            providerFlow.collect { state ->
                when (state) {
                    is DataState.Success<Need> -> {
                        val interceptedData = processAndLog(InterceptPoint.REQUEST_DOWNSTREAM, needType, state.data)
                        emit(DataState.Success(interceptedData))
                    }

                    is DataState.Error<Need> -> {
                        val staleData = state.staleData
                        if (staleData != null) {
                            val interceptedData = processAndLog(InterceptPoint.REQUEST_DOWNSTREAM, needType, staleData)
                            emit(DataState.Error(state.cause, interceptedData))
                        } else {
                            emit(DataState.Error(state.cause, null))
                        }
                    }

                    else -> emit(state)
                }
            }
        }
    }

    // ── Consume (Downstream-intercepted) ────────────────────────────────

    /**
     * See [SwitchBoard.stateFlow].
     *
     * @throws IllegalStateException if the board is closed.
     */
    override fun <T : Any> stateFlow(clazz: KClass<T>): SharedFlow<T> {
        checkOpen()
        return TypedSharedFlow(stateStore.lane(clazz).downstream(), clazz)
    }

    /**
     * See [SwitchBoard.impulseFlow].
     *
     * @throws IllegalStateException if the board is closed.
     */
    override fun <T : Any> impulseFlow(clazz: KClass<T>): SharedFlow<T> {
        checkOpen()
        return TypedSharedFlow(reactionStore.lane(clazz).downstream(), clazz)
    }

    // ── Raw Flow Access ─────────────────────────────────────────────────

    /**
     * See [SwitchBoard.getRawStateFlow].
     *
     * @throws IllegalStateException if the board is closed.
     */
    override fun getRawStateFlow(clazz: KClass<*>): SharedFlow<Any> {
        checkOpen()
        return stateStore.lane(clazz).rawView
    }

    /**
     * See [SwitchBoard.getRawImpulseFlow].
     *
     * @throws IllegalStateException if the board is closed.
     */
    override fun getRawImpulseFlow(clazz: KClass<*>): SharedFlow<Any> {
        checkOpen()
        return reactionStore.lane(clazz).rawView
    }

    // ── Interception ────────────────────────────────────────────────────

    override fun <T : Any> addInterceptor(
        point: InterceptPoint,
        clazz: KClass<T>,
        interceptor: Interceptor<T>,
        priority: Int,
    ): Registration = registryFor(point).add(clazz, interceptor, priority)

    // ── Observation ─────────────────────────────────────────────────────

    /**
     * See [SwitchBoard.addObserver]. Registering on a closed board is allowed
     * but has no effect, since a closed board carries no more data.
     */
    override fun addObserver(observer: SwitchBoardObserver): Registration {
        val handle = ObserverHandle(observer)
        synchronized(observerLock) {
            observerHandles.add(handle)
            publishObserversLocked()
        }
        return handle
    }

    /**
     * Sets a global logger that will be invoked for ALL data passing through the
     * [SwitchBoard], regardless of type, channel, or direction.
     *
     * This is useful for debugging, analytics, or global monitoring without needing
     * to register type-specific interceptors.
     *
     * This is a single slot: setting a new logger replaces the previous one,
     * and `null` removes it. It is independent of observers added via
     * [addObserver] and always runs before them.
     *
     * @param logger A function that receives the [InterceptPoint], the [KClass] of the data, and the data itself.
     */
    @Deprecated(
        "Use addObserver, which supports any number of observers and also delivers the TraceContext.",
        ReplaceWith("addObserver { point, clazz, data, _ -> logger(point, clazz, data) }"),
    )
    fun setGlobalLogger(logger: ((point: InterceptPoint, clazz: KClass<*>, data: Any) -> Unit)?) {
        synchronized(observerLock) {
            legacyLogger = logger?.let { log ->
                SwitchBoardObserver { point, clazz, data, _ -> log(point, clazz, data) }
            }
            publishObserversLocked()
        }
    }

    /**
     * Sets a trace listener that is invoked whenever a [TraceContext] is present
     * in the coroutine context of a stage — typically during a [broadcastState]
     * or [triggerImpulse] call, and for request stages when the collector of a
     * [handleRequest] flow carries a trace. Downstream state/reaction stages run
     * in the board's own sharing coroutines and therefore carry no trace.
     *
     * Unlike [setGlobalLogger], this listener receives the [TraceContext] metadata
     * (emitter tag, trace ID, timestamp) in addition to the intercept point, type,
     * and data. It fires only when tracing is active.
     *
     * This is a single slot: setting a new listener replaces the previous one,
     * and `null` removes it. It runs after the global logger and before
     * observers added via [addObserver].
     *
     * @param listener the callback, or `null` to remove the current listener.
     */
    @Deprecated(
        "Use addObserver, which supports any number of observers; its trace parameter is non-null when tracing is active.",
        ReplaceWith("addObserver { point, clazz, data, trace -> if (trace != null) listener(trace, point, clazz, data) }"),
    )
    fun setTraceListener(listener:
                         (
                             (trace: TraceContext,
                              point: InterceptPoint,
                              clazz: KClass<*>, data: Any) -> Unit
                         )?
    ) {
        synchronized(observerLock) {
            legacyTraceListener = listener?.let { onTrace ->
                SwitchBoardObserver { point, clazz, data, trace ->
                    if (trace != null) onTrace(trace, point, clazz, data)
                }
            }
            publishObserversLocked()
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Tears the board down. Idempotent; only the first call has an effect.
     *
     * - Disposes the [ProviderManager], cancelling all provider jobs.
     * - Cancels the board's own job, stopping every sharing coroutine it
     *   launched. The caller's `scope` is **not** cancelled.
     * - Drops all lanes and observers.
     *
     * Afterwards, [broadcastState], [triggerImpulse], [handleRequest],
     * [stateFlow], [impulseFlow], [getRawStateFlow] and [getRawImpulseFlow]
     * throw [IllegalStateException]. Flows obtained **before** closing are not
     * completed (a [SharedFlow] never completes); they simply stop receiving
     * values, so their collectors should be cancelled by their own lifecycle.
     * Registering interceptors or observers remains allowed but has no effect.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            providerManager.dispose()
        } finally {
            boardJob.cancel(CancellationException("DefaultSwitchBoard was closed"))
            stateStore.clear()
            reactionStore.clear()
            synchronized(observerLock) {
                observerHandles.clear()
                legacyLogger = null
                legacyTraceListener = null
                publishObserversLocked()
            }
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "This DefaultSwitchBoard has been closed" }
    }

    // ── Processing ──────────────────────────────────────────────────────

    /**
     * Processes the provided data through the interceptor pipeline associated
     * with the given intercept point and class, then notifies the registered
     * observers (if any).
     *
     * When no observer is registered this is just the pipeline call: the
     * coroutine context is not even inspected for a [TraceContext].
     *
     * @param point The intercept point specifying where in the processing pipeline the data will be handled.
     * @param clazz The [KClass] of the data type being processed.
     * @param data The input data to process through the interceptors.
     * @return The processed data after it has passed through the interceptor pipeline.
     */
    private suspend fun <T : Any> processAndLog(point: InterceptPoint, clazz: KClass<out T>, data: T): T {
        val processed = pipelineFor(point).applyInterceptors(clazz, data)
        val observers = observerSnapshot
        if (observers.isNotEmpty()) {
            val trace = currentCoroutineContext()[TraceContext]
            for (observer in observers) {
                observer.onData(point, clazz, processed, trace)
            }
        }
        return processed
    }

    /**
     * Handles a failure of the downstream stage for one value: notifies
     * observers, then reports [error] through the standard coroutine
     * exception-handling machinery of the board scope.
     */
    private fun reportDeliveryFailure(point: InterceptPoint, clazz: KClass<*>, value: Any, error: Throwable) {
        for (observer in observerSnapshot) {
            try {
                observer.onDeliveryFailure(point, clazz, value, error)
            } catch (secondary: Throwable) {
                if (secondary !== error) error.addSuppressed(secondary)
            }
        }
        // A failing child of the board's SupervisorJob is routed to the scope's
        // CoroutineExceptionHandler, or the platform default handlers, exactly
        // like any other uncaught coroutine exception — without failing siblings
        // or the caller's scope.
        boardScope.launch(start = CoroutineStart.UNDISPATCHED) { throw error }
    }

    // ── Empty Pipeline Singleton ────────────────────────────────────────

    private object EmptyPipeline : InterceptorPipeline {
        override suspend fun <T : Any> applyInterceptors(clazz: KClass<out T>, data: T): T = data
    }

    private companion object {
        val NO_OBSERVERS: Array<SwitchBoardObserver> = emptyArray()
    }
}

/**
 * Compose [androidx.compose.runtime.CompositionLocal] that provides the current [SwitchBoard] to the
 * composition tree.
 *
 * Accessing this local without a provider throws an [IllegalStateException]
 * with a descriptive message prompting the developer to wrap their UI in a
 * `ProvideSwitchBoard` composable.
 *
 * ### Usage
 *
 * ```kotlin
 * // Providing
 * CompositionLocalProvider(LocalSwitchBoard provides mySwitchBoard) {
 *     MyApp()
 * }
 *
 * // Consuming
 * @Composable
 * fun MyScreen() {
 *     val switchBoard = LocalSwitchBoard.current
 *     // …
 * }
 * ```
 */
val LocalSwitchBoard = compositionLocalOf<SwitchBoard> {
    error("No SwitchBoard provided! Wrap your app in ProvideSwitchBoard.")
}
