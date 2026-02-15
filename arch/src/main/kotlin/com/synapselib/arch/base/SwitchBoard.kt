package com.synapselib.arch.base

import androidx.compose.runtime.compositionLocalOf
import com.synapselib.arch.base.routing.RequestParams
import com.synapselib.arch.base.routing.Router
import com.synapselib.core.typed.TypedSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

/**
 * The direction of data flow relative to the [SwitchBoard].
 *
 * - [UPSTREAM]: data is flowing **into** the board (e.g. a broadcast or trigger
 *   before it reaches the underlying flow/router).
 * - [DOWNSTREAM]: data is flowing **out of** the board (e.g. a value being
 *   delivered to a listener or routed to a handler).
 */
enum class Direction { UPSTREAM, DOWNSTREAM }

/**
 * Identifies the communication channel that data travels through.
 *
 * - [REQUEST]: one-shot request/response interactions routed via a [Router].
 * - [STATE]: persistent, replay-1 state streams (latest value is always cached).
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
 * @property channel the communication channel ([Channel.REQUEST], [Channel.STATE], or [Channel.REACTION]).
 * @property direction whether the interception occurs on the producing
 *                     ([Direction.UPSTREAM]) or consuming ([Direction.DOWNSTREAM]) side.
 */
data class InterceptPoint(val channel: Channel, val direction: Direction)

/**
 * Central message bus that coordinates **requests**, **state**, and **reactions**
 * across an application, with built-in support for interceptors at every stage.
 *
 * ## Three Channels
 *
 * | Channel | Semantics | Replay | Typical use |
 * |---|---|---|---|
 * | **Request** | One-shot routed command → `SharedFlow` of results | 1 | Network calls, navigation |
 * | **State** | Latest-value stream (`replay = 1`) | 1 | UI state, preferences |
 * | **Reaction/Impulse** | Fire-and-forget event stream | 0 | Toasts, analytics pings |
 *
 * ## Flow-First Consumption
 *
 * Every channel exposes a [SharedFlow] with downstream interception already
 * applied. Callers collect, combine, filter, or transform these flows however
 * they like:
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
 * Raw (un-intercepted) flows are available via [getRawStateFlow] and
 * [getRawImpulseFlow] for advanced use cases.
 *
 * ## Interception
 *
 * Every channel supports **upstream** (producer-side) and **downstream**
 * (consumer-side) interceptors. Register them via [addInterceptor] with an
 * [InterceptPoint] that identifies the exact stage.
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
     * Routes a request through the interceptor pipeline and returns a
     * [SharedFlow] of results with `replay = 1`.
     *
     * 1. **Upstream interceptors** (`REQUEST / UPSTREAM`) are applied to [params].
     * 2. The (possibly transformed) params are forwarded to the [Router].
     * 3. **Downstream interceptors** (`REQUEST / DOWNSTREAM`) are applied to
     *    each result before it is emitted into the shared flow.
     *
     * The flow starts lazily on the first subscriber; after that, the result
     * is cached via replay for late collectors.
     *
     * @param Need      the type of the result expected by the caller.
     * @param T         the concrete [RequestParams] subtype.
     * @param needClazz the [KClass] token for [Need], used for type-safe
     *                  downstream interception.
     * @param params    the request parameters describing what to do.
     * @return a [SharedFlow] that emits the intercepted result(s).
     */
    fun <Need : Any, T : RequestParams> handleRequest(
        needClazz: KClass<Need>,
        params: T,
    ): SharedFlow<Need>

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
     * Useful for advanced composition (e.g. combining multiple state flows)
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
}

// ── Reified Extension Functions ─────────────────────────────────────────


/** Reified convenience overload for [SwitchBoard.broadcastState]. */
suspend inline fun <reified T : Any> SwitchBoard.broadcastState(data: T) =
    broadcastState(T::class, data)

/** Reified convenience overload for [SwitchBoard.triggerImpulse]. */
suspend inline fun <reified T : Any> SwitchBoard.triggerImpulse(data: T) =
    triggerImpulse(T::class, data)

/** Reified convenience overload for [SwitchBoard.handleRequest]. */
inline fun <reified Need : Any, reified T : RequestParams> SwitchBoard.handleRequest(
    params: T,
): SharedFlow<Need> = handleRequest(Need::class, params)

/** Reified convenience overload for [SwitchBoard.addInterceptor]. */
inline fun <reified T : Any> SwitchBoard.addInterceptor(
    point: InterceptPoint,
    interceptor: Interceptor<T>,
    priority: Int = 0,
): Registration = addInterceptor(point, T::class, interceptor, priority)

annotation class SwitchBoardStopTimeout
annotation class SwitchBoardReplayExpiration

// ── Implementation ──────────────────────────────────────────────────────

/**
 * Default [SwitchBoard] implementation backed by Kotlin [SharedFlow]s and
 * a [Router] for request dispatch.
 *
 * ## Internal Architecture
 *
 * ```
 *                     ┌──────────────────────────┐
 *                     │      SwitchBoardImpl     │
 *                     │                          │
 *  broadcastState ──▶ │  upstream interceptors   │
 *                     │         ↓                │
 *                     │   MutableSharedFlow      │
 *                     │         ↓                │
 *  listenToState  ◀── │  downstream interceptors │
 *                     │                          │
 *  handleRequest ───▶ │  upstream interceptors   │
 *                     │         ↓                │
 *                     │       Router             │
 *                     │         ↓                │
 *                     │  downstream interceptors │ ──▶ Flow<Need>
 *                     └──────────────────────────┘
 * ```
 *
 * - **State channels**: `MutableSharedFlow(replay = 1)` — the latest value is
 *   always cached and delivered immediately to new collectors.
 * - **Reaction channels**: `MutableSharedFlow(replay = 0, extraBufferCapacity = 64)`
 *   — fire-and-forget events with a generous buffer to absorb bursts.
 * - **Interceptors**: stored per [InterceptPoint] in separate [InterceptorRegistry]
 *   instances, allowing independent priority ordering for each stage.
 *
 * ## Thread Safety
 *
 * All internal maps are [ConcurrentHashMap]s and all flows are thread-safe.
 * Interceptor registration and pipeline execution may happen concurrently.
 *
 * @param router the [Router] used to dispatch request-channel commands.
 *               Defaults to a fresh [Router] instance.
 */
class DefaultSwitchBoard @Inject constructor(
    private val router: Router,
    private val scope: CoroutineScope,
    @property:SwitchBoardStopTimeout val stopTimeoutMillis: Long = 3.seconds.inWholeMilliseconds,
    @property:SwitchBoardReplayExpiration val replayExpirationMillis: Long = 3.seconds.inWholeMilliseconds
) : SwitchBoard {

    /** Per-type state flows. Each flow has `replay = 1` so collectors get the latest value. */
    private val stateChannels = ConcurrentHashMap<KClass<*>, MutableSharedFlow<Any>>()

    /** Per-type reaction flows. No replay; `extraBufferCapacity = 64` absorbs emission bursts. */
    private val reactionChannels = ConcurrentHashMap<KClass<*>, MutableSharedFlow<Any>>()

    /** Cached downstream-intercepted state flows, one per type. All consumers share this instance. */
    private val downstreamStateFlows = ConcurrentHashMap<KClass<*>, SharedFlow<Any>>()

    /** Cached downstream-intercepted impulse flows, one per type. All consumers share this instance. */
    private val downstreamImpulseFlows = ConcurrentHashMap<KClass<*>, SharedFlow<Any>>()

    /**
     * Interceptor registries keyed by [InterceptPoint].
     *
     * Each unique (channel, direction) pair gets its own [InterceptorRegistry],
     * so interceptors registered for e.g. `(STATE, UPSTREAM)` never interfere
     * with those registered for `(STATE, DOWNSTREAM)`.
     */
    private val interceptors = ConcurrentHashMap<InterceptPoint, InterceptorRegistry>()

    private fun registryFor(point: InterceptPoint): InterceptorRegistry =
        interceptors.computeIfAbsent(point) { InterceptorRegistry() }

    private fun pipelineFor(point: InterceptPoint): InterceptorPipeline =
        interceptors[point] ?: EmptyPipeline

    // ── Internal Flow Factories ─────────────────────────────────────────

    private fun mutableStateFlow(clazz: KClass<*>): MutableSharedFlow<Any> =
        stateChannels.computeIfAbsent(clazz) {
            MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    private fun mutableReactionFlow(clazz: KClass<*>): MutableSharedFlow<Any> =
        reactionChannels.computeIfAbsent(clazz) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    // ── Produce (Upstream) ──────────────────────────────────────────────

    override suspend fun <T : Any> broadcastState(clazz: KClass<T>, data: T) {
        val upstream = InterceptPoint(Channel.STATE, Direction.UPSTREAM)
        val processed = pipelineFor(upstream).applyInterceptors(clazz, data)
        mutableStateFlow(clazz).emit(processed)
    }

    override suspend fun <T : Any> triggerImpulse(clazz: KClass<T>, data: T) {
        val upstream = InterceptPoint(Channel.REACTION, Direction.UPSTREAM)
        val processed = pipelineFor(upstream).applyInterceptors(clazz, data)
        mutableReactionFlow(clazz).emit(processed)
    }

    override fun <Need : Any, T : RequestParams> handleRequest(
        needClazz: KClass<Need>,
        params: T,
    ): SharedFlow<Need> {
        val upstream = InterceptPoint(Channel.REQUEST, Direction.UPSTREAM)
        val downstream = InterceptPoint(Channel.REQUEST, Direction.DOWNSTREAM)

        return flow {
            val processedParams = pipelineFor(upstream)
                .applyInterceptors(params::class, params)

            router.route(needClazz, processedParams).collect { value ->
                val processed = pipelineFor(downstream)
                    .applyInterceptors(needClazz, value)
                emit(processed)
            }
        }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis, replayExpirationMillis), replay = 1)
    }

    // ── Consume (Downstream-intercepted) ────────────────────────────────

    override fun <T : Any> stateFlow(clazz: KClass<T>): SharedFlow<T> {
        val shared = downstreamStateFlows.computeIfAbsent(clazz) {
            val downstream = InterceptPoint(Channel.STATE, Direction.DOWNSTREAM)
            mutableStateFlow(clazz)
                .map { pipelineFor(downstream).applyInterceptors(clazz, clazz.java.cast(it)) }
                .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis, replayExpirationMillis), replay = 1)
        }
        return TypedSharedFlow(shared, clazz)
    }

    override fun <T : Any> impulseFlow(clazz: KClass<T>): SharedFlow<T> {
        val shared = downstreamImpulseFlows.computeIfAbsent(clazz) {
            val downstream = InterceptPoint(Channel.REACTION, Direction.DOWNSTREAM)
            mutableReactionFlow(clazz)
                .map { pipelineFor(downstream).applyInterceptors(clazz, clazz.java.cast(it)) }
                .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis, replayExpirationMillis), replay = 0)
        }
        return TypedSharedFlow(shared, clazz)
    }

    // ── Raw Flow Access ─────────────────────────────────────────────────

    override fun getRawStateFlow(clazz: KClass<*>): SharedFlow<Any> =
        mutableStateFlow(clazz).asSharedFlow()

    override fun getRawImpulseFlow(clazz: KClass<*>): SharedFlow<Any> =
        mutableReactionFlow(clazz).asSharedFlow()

    // ── Interception ────────────────────────────────────────────────────

    override fun <T : Any> addInterceptor(
        point: InterceptPoint,
        clazz: KClass<T>,
        interceptor: Interceptor<T>,
        priority: Int,
    ): Registration = registryFor(point).add(clazz, interceptor, priority)

    // ── Empty Pipeline Singleton ────────────────────────────────────────

    private object EmptyPipeline : InterceptorPipeline {
        override suspend fun <T : Any> applyInterceptors(clazz: KClass<out T>, data: T): T = data
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