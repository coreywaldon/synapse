package com.synapselib.arch.base

import com.synapselib.core.typed.DataState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.reflect.KClass

/**
 * Common base for every SynapseLib DSL receiver — [ContextScope], [NodeScope],
 * [CoordinatorScope] and [com.synapselib.arch.base.provider.ProviderScope].
 *
 * A `SynapseScope` pairs a [SwitchBoard] with an optional emitter [tag] and
 * implements the **shared verbs** exactly once:
 *
 * | Verb | Returns | Notes |
 * |---|---|---|
 * | [Broadcast]`(data)` | — (suspending) | state channel, traced when [tag] is set |
 * | [Trigger]`(event)` | — (suspending) | reaction channel, traced when [tag] is set |
 * | [ListenFor]`<O>()` | `SharedFlow<O>` | downstream-intercepted state flow |
 * | [ReactTo]`<A>()` | `SharedFlow<A>` | downstream-intercepted impulse flow |
 * | [Request]`(impulse)` | `Flow<DataState<Need>>` | cold request flow, traced when [tag] is set |
 *
 * Subclasses add their own *handler* overloads (the `@Composable` listeners on
 * [NodeScope], the `launch` + `collect` listeners on [CoordinatorScope]). Those
 * overloads take a trailing lambda and therefore never clash with the
 * flow-returning verbs declared here.
 *
 * ### Why an abstract class and not an interface?
 *
 * The verbs are `inline` with `reified` type parameters so that the
 * `KClass` token is captured at the call site. Interface members cannot be
 * `inline`, and top-level extension functions would force every caller in
 * another package to add explicit imports (`import com.synapselib.arch.base.Trigger`),
 * breaking existing call sites. Final inline *members* on an abstract class
 * keep `Trigger(x)` / `Broadcast(x)` resolvable with only the scope type
 * imported, exactly as before.
 *
 * You can still write your own reusable helpers against this type:
 *
 * ```kotlin
 * suspend fun SynapseScope.announceLogin(user: User) {
 *     Broadcast(SessionState.Authenticated(user))
 *     Trigger(NavigateTo("home"))
 * }
 * ```
 *
 * ## Tracing
 *
 * When [tag] is non-null, every [Broadcast] / [Trigger] creates a **new**
 * [TraceContext] (a new trace point with `emitterTag = tag`) and runs the
 * emission inside it. If the calling coroutine already carries a
 * [TraceContext], the new trace's [TraceContext.parentTraceId] is set to the
 * ambient trace's [TraceContext.traceId], so causal chains can be rebuilt by
 * following parent links. [Request] does the same for the request pipeline
 * (upstream interception of the impulse and downstream interception of each
 * result run inside the new trace).
 *
 * When [tag] is `null` the scope is **transparent**: no context element is
 * created and no context lookup happens. The emission simply runs in the
 * caller's coroutine context, so an ambient [TraceContext] (if any) is
 * reported by the [SwitchBoard]'s trace listener unchanged.
 *
 * ### What is and is not propagated
 *
 * - **Propagated:** emissions made in a coroutine that carries a
 *   [TraceContext] — e.g. `withTrace("Checkout") { Broadcast(a); Trigger(b) }`,
 *   or a tagged scope's `Broadcast` called from inside another tagged scope's
 *   traced work. Each tagged emission links to the ambient trace.
 * - **Not propagated:** the hop *through* the [SwitchBoard]. State and impulse
 *   flows are `SharedFlow`s that deliberately do not store trace metadata, so a
 *   `ReactTo` / `ListenFor` handler does **not** run inside the trace of the
 *   event it is handling, and a [com.synapselib.arch.base.provider.Provider]
 *   runs in the provider manager's coroutine (deduplicated across many
 *   requesters), not in the requester's trace. To link such work manually,
 *   capture the trace at the emission site (e.g. in an upstream interceptor via
 *   [currentTraceContext]) and re-enter it with `withContext(trace.child(tag))`.
 */
abstract class SynapseScope {

    /** The [SwitchBoard] all verbs of this scope communicate through. */
    abstract val switchboard: SwitchBoard

    /**
     * Optional emitter label for [TraceContext]. When non-null, [Broadcast],
     * [Trigger] and [Request] run inside a fresh [TraceContext] whose
     * `emitterTag` is this value (and whose `parentTraceId` links to any
     * ambient trace). When `null`, no tracing overhead is incurred.
     */
    abstract val tag: String?

    // ── Imperative Actions ──────────────────────────────────────────────

    /**
     * Broadcasts [data] into the [SwitchBoard]'s state channel.
     *
     * This is a **suspending, non-composable** function. Call it from a
     * coroutine (e.g., inside `LaunchedEffect` or `scope.launch`):
     *
     * ```kotlin
     * LaunchedEffect(Unit) {
     *     Broadcast(UserProfile(name = "Alice"))
     * }
     * ```
     *
     * Upstream state interceptors are applied before emission. When [tag] is
     * set, the emission is traced (see the class-level *Tracing* section).
     *
     * @param O    the state type (inferred).
     * @param data the value to broadcast.
     * @see SwitchBoard.broadcastState
     */
    @Suppress("FunctionName")
    suspend inline fun <reified O : Any> Broadcast(data: O) {
        if (tag == null) switchboard.broadcastState(O::class, data)
        else emitTraced(Channel.STATE, O::class, data)
    }

    /**
     * Triggers a fire-and-forget [Impulse] on the [SwitchBoard]'s reaction channel.
     *
     * This is a **suspending, non-composable** function:
     *
     * ```kotlin
     * Button(onClick = { scope.launch { Trigger(ShowToast("Saved!")) } }) {
     *     Text("Save")
     * }
     * ```
     *
     * Upstream reaction interceptors are applied before emission. When [tag]
     * is set, the emission is traced (see the class-level *Tracing* section).
     *
     * @param A     the [Impulse] subtype (inferred).
     * @param event the impulse event to emit.
     * @see SwitchBoard.triggerImpulse
     */
    @Suppress("FunctionName")
    suspend inline fun <reified A : Impulse> Trigger(event: A) {
        if (tag == null) switchboard.triggerImpulse(A::class, event)
        else emitTraced(Channel.REACTION, A::class, event)
    }

    // ── Flow Access ─────────────────────────────────────────────────────

    /**
     * Returns the downstream-intercepted [SharedFlow] for state values of
     * type [O].
     *
     * Use this when you need to compose, filter, debounce, or otherwise
     * transform the flow before collecting:
     *
     * ```kotlin
     * launch {
     *     combine(ListenFor<AppConfig>(), ListenFor<FeatureFlags>()) { config, flags ->
     *         // …
     *     }.collectLatest { /* react */ }
     * }
     * ```
     *
     * @param O the state type (inferred).
     * @return a [SharedFlow] with `replay = 1` emitting intercepted state values.
     * @see SwitchBoard.stateFlow
     */
    @Suppress("FunctionName")
    inline fun <reified O : Any> ListenFor(): SharedFlow<O> =
        switchboard.stateFlow(O::class)

    /**
     * Returns the downstream-intercepted [SharedFlow] for impulse events of
     * type [A].
     *
     * ```kotlin
     * launch {
     *     ReactTo<ShowToast>().filter { it.priority == HIGH }.collect { /* show toast */ }
     * }
     * ```
     *
     * @param A the [Impulse] subtype (inferred).
     * @return a [SharedFlow] with `replay = 0` emitting intercepted impulse events.
     * @see SwitchBoard.impulseFlow
     */
    @Suppress("FunctionName")
    inline fun <reified A : Impulse> ReactTo(): SharedFlow<A> =
        switchboard.impulseFlow(A::class)

    /**
     * Returns the cold [Flow] of [DataState] results for routing [impulse]
     * through the [SwitchBoard]'s request pipeline.
     *
     * See [SwitchBoard.handleRequest] for the dedup/replay semantics of the
     * underlying provider. When [tag] is set, each collection runs the request
     * pipeline inside a fresh [TraceContext] (linked to the collector's ambient
     * trace, if any).
     *
     * @param Need    the expected result type (inferred from [I]).
     * @param I       the [DataImpulse] subtype describing the request.
     * @param impulse parameters forwarded to the request pipeline.
     * @return a cold [Flow] emitting intercepted [DataState] results.
     */
    @Suppress("FunctionName")
    inline fun <reified Need : Any, reified I : DataImpulse<Need>> Request(
        impulse: I,
    ): Flow<DataState<Need>> = traced(switchboard.handleRequest(I::class, Need::class, impulse))

    // ── Tracing internals ───────────────────────────────────────────────

    /**
     * Slow path of [Broadcast] / [Trigger], only reached when [tag] is
     * non-null. Kept out of line so the inline verbs stay tiny.
     */
    @PublishedApi
    internal suspend fun <T : Any> emitTraced(channel: Channel, clazz: KClass<T>, data: T) {
        withContext(TraceContext.childOf(currentCoroutineContext()[TraceContext], tag)) {
            when (channel) {
                Channel.STATE -> switchboard.broadcastState(clazz, data)
                Channel.REACTION -> switchboard.triggerImpulse(clazz, data)
                Channel.REQUEST -> error("Requests are traced via traced(flow)")
            }
        }
    }

    /**
     * Wraps a request [flow] so that, when [tag] is set, every collection runs
     * the upstream pipeline inside a fresh [TraceContext] linked to the
     * collector's ambient trace. Returns [flow] unchanged when [tag] is `null`.
     *
     * Uses [flowOn] with a context that only adds a [TraceContext] element;
     * since the dispatcher is unchanged, no channel or dispatch is introduced.
     */
    @PublishedApi
    internal fun <T> traced(flow: Flow<T>): Flow<T> {
        val emitter = tag ?: return flow
        return flow {
            val trace = TraceContext.childOf(currentCoroutineContext()[TraceContext], emitter)
            emitAll(flow.flowOn(trace))
        }
    }
}

// ── Registration tracking ───────────────────────────────────────────────

/**
 * Thread-safe bookkeeping for [Registration]s owned by a disposable scope
 * ([NodeScope], [CoordinatorScope]).
 *
 * - [track] adds a registration unless the owner is already disposed, in which
 *   case the registration is **unregistered immediately** (so late
 *   registrations from racing handler coroutines can never leak).
 * - [dispose] atomically marks the tracker disposed, drains the list and
 *   unregisters everything that was in it. It is idempotent.
 *
 * [registrations] is a synchronized list; compound operations synchronize on
 * the list itself.
 */
internal class RegistrationTracker {

    /** Live registrations. Synchronized; compound ops lock on this list. */
    val registrations: MutableList<Registration> = Collections.synchronizedList(ArrayList())

    @Volatile
    var isDisposed: Boolean = false
        private set

    /**
     * Tracks [registration] for cleanup on [dispose] and returns a handle whose
     * [Registration.unregister] also stops tracking it. If already disposed,
     * [registration] is unregistered immediately and returned as-is.
     */
    fun track(registration: Registration): Registration {
        val accepted = synchronized(registrations) {
            if (isDisposed) false else registrations.add(registration)
        }
        if (!accepted) {
            registration.unregister()
            return registration
        }
        return Registration {
            synchronized(registrations) { registrations.remove(registration) }
            registration.unregister()
        }
    }

    /** Marks disposed, then unregisters and clears every tracked registration. */
    fun dispose() {
        val drained = synchronized(registrations) {
            isDisposed = true
            val copy = registrations.toList()
            registrations.clear()
            copy
        }
        drained.forEach { it.unregister() }
    }
}
