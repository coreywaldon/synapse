package com.synapselib.arch.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Base type for fire-and-forget events (reactions) that flow through the
 * [SwitchBoard]'s reaction channel.
 *
 * Subclass [Impulse] to define domain-specific events:
 * ```kotlin
 * data class ShowToast(val message: String) : Impulse()
 * data class NavigateTo(val route: String) : Impulse()
 * ```
 *
 * Impulses are emitted via [SynapseScope.Trigger] (available on every DSL
 * scope) and consumed via [NodeScope.ReactTo], [CoordinatorScope.ReactTo] or
 * the flow-returning [SynapseScope.ReactTo].
 *
 * Unlike state (which replays the latest value), impulses have **no replay** —
 * only active listeners receive them.
 */
open class Impulse

/**
 * Top-level composable that establishes a **context boundary** for the
 * SynapseLib DSL.
 *
 * A context pairs an arbitrary value [context] (typically a state, config
 * object, or domain model) with the nearest [SwitchBoard] from the composition,
 * wrapping them in a [ContextScope] that child [Node]s can access.
 *
 * ### Usage
 *
 * ```kotlin
 * @Composable
 * fun MyFeature() {
 *     CreateContext(myState) {
 *         // `this` is ContextScope<MyViewModel>
 *         Node(initialState = UiState()) {
 *             // `this` is NodeScope<MyViewModel, UiState>
 *             // context == myState
 *         }
 *     }
 * }
 * ```
 *
 * The [ContextScope] is `remember`ed by [context], the [SwitchBoard], and
 * [tag], so it is recreated only when any of those identities changes.
 *
 * @param T       the type of the context value.
 * @param context the value made available to all child scopes via
 *                [ContextScope.context].
 * @param tag     optional human-readable label (e.g., `"CheckoutScreen"`)
 *                attached to a [TraceContext] on every `Trigger`, `Broadcast`
 *                and `Request` made through the context or its nodes. When `null` (the default),
 *                no tracing overhead is incurred.
 * @param block   composable content that runs inside the [ContextScope].
 */
@Composable
inline fun <T> CreateContext(
    context: T,
    tag: String? = null,
    crossinline block: @Composable ContextScope<T>.() -> Unit,
) {
    val switchboard = LocalSwitchBoard.current
    val coroutineScope = rememberCoroutineScope()
    val scope = remember(context, switchboard, tag) {
        ContextScope(context, switchboard, coroutineScope, tag)
    }
    key(scope) {
        scope.block()
    }
}

/**
 * Holds the shared **context value** and [SwitchBoard] reference for a subtree
 * of the composition.
 *
 * Created by [CreateContext] and consumed by [Node] to construct [NodeScope]s.
 * This class is intentionally minimal — it exists primarily as a typed carrier
 * so that [Node] can access both the context and the switchboard without
 * threading them through parameters manually.
 *
 * The SwitchBoard verbs ([Broadcast], [Trigger], and the flow-returning
 * [ListenFor]`()`, [ReactTo]`()` and [Request]`(impulse)`) are inherited from
 * [SynapseScope].
 *
 * @param T                 the type of the context value.
 * @property context        the domain object shared across all [Node]s in this
 *                          context boundary (e.g., a ViewModel).
 * @property switchboard    the [SwitchBoard] used for all cross-node communication
 *                          (state broadcasts, reactions, requests, interception).
 * @property coroutineScope a [CoroutineScope] available for callers within a
 *                          [ContextScope]. [CreateContext] always supplies a
 *                          composition-bound scope (`rememberCoroutineScope()`),
 *                          which is canceled when the context leaves the
 *                          composition. **The default value is an unowned
 *                          `CoroutineScope(SupervisorJob() + Dispatchers.Default)`
 *                          that nothing ever cancels** — it exists only for
 *                          convenience when constructing a `ContextScope`
 *                          manually (e.g. in tests). Pass an owned scope
 *                          whenever coroutines launched from it must not
 *                          outlive the caller.
 * @property tag            optional emitter label for [TraceContext]. When non-null,
 *                          every [Trigger], [Broadcast] and [Request] runs inside a
 *                          fresh `TraceContext(emitterTag = tag)` linked to any
 *                          ambient trace.
 */
class ContextScope<T>(
    val context: T,
    override val switchboard: SwitchBoard,
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    override val tag: String? = null,
) : SynapseScope()

/**
 * Composable that creates a **stateful node** inside a [ContextScope].
 *
 * A node is the fundamental unit of the SynapseLib Compose DSL. It pairs:
 * - **Local state** of type [S], managed via [NodeScope.update].
 * - **SwitchBoard capabilities**: broadcasting, triggering, listening,
 *   requesting, and intercepting — all lifecycle-aware.
 *
 * The node's [NodeScope] is `remember`ed across recompositions and
 * automatically [disposed][NodeScope.dispose] when it leaves the composition,
 * which unregisters any interceptors added via [NodeScope.addInterceptor]
 * ([NodeScope.Intercept] registrations are owned by their own effects).
 *
 * ### Usage
 *
 * ```kotlin
 * CreateContext(myState) {
 *     Node(initialState = ScreenState()) {
 *         // Read state
 *         Text("Count: ${state.count}")
 *
 *         // Update state
 *         Button(onClick = { update { it.copy(count = it.count + 1) } }) {
 *             Text("Increment")
 *         }
 *
 *         // Listen for external state changes
 *         ListenFor<UserProfile> { profile ->
 *             update { it.copy(userName = profile.name) }
 *         }
 *
 *         // React to one-shot events
 *         ReactTo<ShowToast> { toast ->
 *             // show snackbar…
 *         }
 *     }
 * }
 * ```
 *
 * @param C            the context type from the enclosing [ContextScope].
 * @param S            the local state type; must be a non-nullable [Any] subtype.
 * @param initialState the starting value for the local state. Only used on the first
 *                     composition — later recompositions retain the
 *                     existing state.
 * @param block        composable content that runs inside the [NodeScope].
 */
@Composable
inline fun <C, reified S : Any> ContextScope<C>.Node(
    initialState: S,
    crossinline block: @Composable NodeScope<C, S>.() -> Unit,
) : NodeScope<C, S> {
    val stateHolder = rememberState(initialState)
    val coroutineScope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val nodeScope = remember(this, coroutineScope) {
        NodeScope(this, stateHolder, coroutineScope)
    }

    DisposableEffect(nodeScope) {
        onDispose { nodeScope.dispose() }
    }

    nodeScope.block()

    return nodeScope
}

/**
 * Thread-safe cache of serializer lookup results, keyed by [KClass].
 *
 * Avoids repeated exception-based control flow for non-`@Serializable`
 * types: the first lookup per type pays the try/catch cost, and all
 * subsequent lookups are a single map read.
 */
@PublishedApi
internal val serializerCache = java.util.concurrent.ConcurrentHashMap<KClass<*>, Any>()

/** Sentinel stored in [serializerCache] to represent a type with no serializer. */
@PublishedApi
internal object NoSerializer

/**
 * Returns the [KSerializer] for [S] if it is `@Serializable`, or `null` otherwise.
 *
 * Results are cached per type in [serializerCache] so that the try/catch
 * (which allocates a stack trace on failure) only runs once per type over
 * the lifetime of the process.
 */
inline fun <reified S : Any> serializerOrNull(): KSerializer<S>? {
    val cached = serializerCache[S::class]
    if (cached != null) {
        @Suppress("UNCHECKED_CAST")
        return if (cached === NoSerializer) null else cached as KSerializer<S>
    }
    val result = try { serializer<S>() } catch (_: SerializationException) { null }
    serializerCache[S::class] = result ?: NoSerializer
    @Suppress("UNCHECKED_CAST")
    return result
}

@Composable
inline fun <reified S : Any> rememberState(
    initialState: S,
): MutableState<S> {
    val ser = serializerOrNull<S>()

    return if (ser != null) {
        val saver = Saver<S, String>(
            save = { Json.encodeToString(ser, it) },
            restore = { Json.decodeFromString(ser, it) },
        )
        rememberSaveable(stateSaver = saver) { mutableStateOf(initialState) }
    } else {
        remember { mutableStateOf(initialState) }
    }
}

/**
 * The primary DSL receiver inside a [Node], providing local state management
 * and full access to the [SwitchBoard].
 *
 * ## Capabilities
 *
 * | Category | Methods | Composable? |
 * |---|---|---|
 * | **Local state** | [update] | No — call from callbacks |
 * | **State broadcast** | [Broadcast] | No — suspending |
 * | **Reactions** | [Trigger] | No — suspending |
 * | **Interception** | [Intercept] | Yes — lifecycle-aware |
 * | **Interception (imperative)** | [addInterceptor] | No — returns [Registration] |
 * | **Request** | [Request]`(impulse) { }` | Yes — lifecycle-aware |
 * | **Listen (state)** | [ListenFor]`{ }` | Yes — lifecycle-aware |
 * | **Listen (reaction)** | [ReactTo]`{ }` | Yes — lifecycle-aware |
 * | **Flows** | [ListenFor]`()`, [ReactTo]`()`, [Request]`(impulse)` | No — return flows |
 *
 * [Broadcast], [Trigger] and the flow-returning overloads are inherited from
 * [SynapseScope]; [switchboard] and [tag] come from the enclosing [contextScope].
 *
 * ### Lifecycle
 *
 * The scope is created when the [Node] enters the composition and
 * [disposed][dispose] when it leaves. Disposal unregisters every interceptor
 * added via [addInterceptor]; afterwards [addInterceptor] unregisters new
 * registrations immediately (see [isDisposed]).
 *
 * Long-lived listeners ([ListenFor], [ReactTo], [Request]) and composable
 * interceptors ([Intercept]) are managed via [DisposableEffect] and canceled
 * automatically when their keys change or the composable is removed.
 *
 * @param C the context type from the enclosing [ContextScope].
 * @param S the local state type.
 * @property context read-only access to the value provided by [CreateContext].
 * @property state   the current snapshot of the node's local state. Reads
 *                   during composition will trigger recomposition when the
 *                   value changes.
 */
class NodeScope<C, S : Any>(
    val contextScope: ContextScope<C>,
    val stateHolder: MutableState<S>,
    val scope: CoroutineScope,
) : SynapseScope() {
    val context: C get() = contextScope.context
    val state: S get() = stateHolder.value

    /** The [SwitchBoard] inherited from the enclosing [ContextScope]. */
    override val switchboard: SwitchBoard get() = contextScope.switchboard

    /** The trace tag inherited from the enclosing [ContextScope]. */
    override val tag: String? get() = contextScope.tag

    private val tracker = RegistrationTracker()

    /**
     * Interceptor registrations made via [addInterceptor] that will be
     * [unregistered][Registration.unregister] on [dispose]. Synchronized list.
     */
    internal val registrations: MutableList<Registration> get() = tracker.registrations

    /** `true` once [dispose] has run. */
    val isDisposed: Boolean get() = tracker.isDisposed

    /**
     * Tears down this scope by unregistering all interceptors added via
     * [addInterceptor]. Idempotent and thread-safe.
     *
     * Called automatically by the [DisposableEffect] inside [Node] when the
     * node leaves the composition. Should not normally be called manually.
     */
    @PublishedApi
    internal fun dispose() {
        tracker.dispose()
    }

    /** Tracks [registration] for disposal; see [RegistrationTracker.track]. */
    @PublishedApi
    internal fun trackRegistration(registration: Registration): Registration =
        tracker.track(registration)

    // ── State Update ────────────────────────────────────────────────────

    /**
     * Applies [reducer] to the current state and stores the result.
     *
     * This is a **synchronous, non-composable** operation — call it from
     * click handlers, callbacks, or inside `LaunchedEffect`:
     *
     * ```kotlin
     * Button(onClick = { update { it.copy(count = it.count + 1) } }) {
     *     Text("+1")
     * }
     * ```
     *
     * @param reducer pure function that maps the current state to the next state.
     */
    fun update(reducer: (S) -> S) {
        stateHolder.value = reducer(stateHolder.value)
    }

    // ── Interceptor Registration ────────────────────────────────────────

    /**
     * Installs an [interceptor] at the given [point] for data of type [T] for
     * as long as this call stays in the composition.
     *
     * The interceptor is registered **once** per distinct ([point],
     * [priority], [key]) and unregistered when the call leaves the composition
     * or one of those values changes. Recompositions do not re-register; the
     * most recent [interceptor] instance passed in is always the one invoked
     * (it is held via [rememberUpdatedState]), so capturing fresh state in
     * the lambda is safe:
     *
     * ```kotlin
     * Node(initialState = MyState()) {
     *     Intercept<NetworkRequest>(
     *         point = InterceptPoint(Channel.REQUEST, Direction.UPSTREAM),
     *         interceptor = Interceptor.transform { req -> req.copy(token = state.authToken) },
     *     )
     * }
     * ```
     *
     * This is `@Composable`; from callbacks or coroutines use [addInterceptor].
     *
     * @param T           the data type the interceptor operates on.
     * @param point       the [InterceptPoint] (channel and direction) to intercept.
     * @param interceptor the [Interceptor] to install.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     * @param key         re-registration key; change it to remove and re-add
     *                    the interceptor. Defaults to [Unit] (stable).
     */
    @Suppress("FunctionName")
    @Composable
    inline fun <reified T : Any> Intercept(
        point: InterceptPoint,
        interceptor: Interceptor<T>,
        priority: Int = 0,
        key: Any? = Unit,
    ) {
        InterceptEffect(T::class, point, interceptor, priority, key)
    }

    /** Non-reified body of [Intercept]. */
    @PublishedApi
    @Composable
    internal fun <T : Any> InterceptEffect(
        clazz: KClass<T>,
        point: InterceptPoint,
        interceptor: Interceptor<T>,
        priority: Int,
        key: Any?,
    ) {
        val current = rememberUpdatedState(interceptor)
        DisposableEffect(this, point, clazz, priority, key) {
            val registration = switchboard.addInterceptor(
                point,
                clazz,
                Interceptor { data, chain -> current.value.intercept(data, chain) },
                priority,
            )
            onDispose { registration.unregister() }
        }
    }

    /**
     * Imperatively registers an [interceptor] at the given [point] for data of
     * type [T], automatically unregistering it when this node is disposed.
     *
     * Unlike [Intercept] this is **not** composable — every call registers a
     * new interceptor, so call it from callbacks, effects, or coroutines, never
     * directly from the composable body:
     *
     * ```kotlin
     * LaunchedEffect(userId) {
     *     val reg = addInterceptor(InterceptPoint.REACTION_UPSTREAM, tagWith<Analytics>(userId))
     *     try { awaitCancellation() } finally { reg.unregister() }
     * }
     * ```
     *
     * Thread-safe. If the node is already disposed, the interceptor is
     * unregistered immediately and the returned handle is inert.
     *
     * @param T           the data type the interceptor operates on.
     * @param point       the [InterceptPoint] (channel and direction) to intercept.
     * @param interceptor the [Interceptor] to install.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     * @return a [Registration]; calling [Registration.unregister] removes the
     *         interceptor and stops tracking it.
     */
    inline fun <reified T : Any> addInterceptor(
        point: InterceptPoint,
        interceptor: Interceptor<T>,
        priority: Int = 0,
    ): Registration =
        trackRegistration(switchboard.addInterceptor(point, T::class, interceptor, priority))

    // ── Side Effects (lifecycle-aware, @Composable) ─────────────────────

    /**
     * Launches a request through the [SwitchBoard] and delivers each result
     * to [callback] by collecting the returned cold [kotlinx.coroutines.flow.Flow].
     *
     * The request is canceled and relaunched whenever [key] changes, and
     * canceled when this composable leaves the composition.
     *
     * ```kotlin
     * Request<UserProfile, FetchUserParams>(
     *     impulse = FetchUserParams(userId = 42),
     * ) { profile ->
     *     update { it.copy(user = profile) }
     * }
     * ```
     *
     * @param Need     the expected result type from the request handler.
     * @param I        the [DataImpulse] subtype describing the request.
     * @param impulse  parameters forwarded to the [SwitchBoard]'s request pipeline.
     * @param key      recomposition key; defaults to [impulse]. When the key
     *                 changes, the in-flight request is canceled and a new one
     *                 is launched.
     * @param callback invoked on the main dispatcher with each emitted result.
     *                 Wrapped with [rememberUpdatedState] so the latest lambda
     *                 is always called, even if the enclosing scope recomposes.
     */
    @Suppress("FunctionName")
    @Composable
    inline fun <reified Need : Any, reified I : DataImpulse<Need>> Request(
        impulse: I,
        key: Any? = impulse,
        noinline callback: (DataState<Need>) -> Unit,
    ) {
        val currentCallback by rememberUpdatedState(callback)
        DisposableEffect(this, key) {
            val stateFlow = traced(switchboard.handleRequest(I::class, Need::class, impulse))

            val job = scope.launch {
                stateFlow.collect { currentCallback(it) }
            }
            onDispose { job.cancel() }
        }
    }

    /**
     * Subscribes to [Impulse] events of type [A] from the [SwitchBoard]'s
     * reaction channel for the lifetime of this composable, delivering each
     * event to [handler].
     *
     * The subscription is canceled and restarted when [reactionKey] changes.
     *
     * ```kotlin
     * ReactTo<NavigateTo> { event ->
     *     navController.navigate(event.route)
     * }
     * ```
     *
     * @param A           the [Impulse] subtype to listen for (inferred).
     * @param reactionKey recomposition key; defaults to [Unit] (stable).
     *                    Change this to restart the subscription.
     * @param handler     invoked for each received impulse. Wrapped with
     *                    [rememberUpdatedState] for safe recomposition.
     */
    @Suppress("FunctionName")
    @Composable
    inline fun <reified A : Impulse> ReactTo(
        reactionKey: Any? = Unit,
        noinline handler: (A) -> Unit,
    ) {
        val currentHandler by rememberUpdatedState(handler)
        DisposableEffect(this, reactionKey) {
            val job = scope.launch {
                switchboard.impulseFlow(A::class).collect { currentHandler(it) }
            }
            onDispose { job.cancel() }
        }
    }

    /**
     * Subscribes to state values of type [O] from the [SwitchBoard]'s state
     * channel for the lifetime of this composable, delivering each value
     * to [handler].
     *
     * Because state flows use `replay = 1`, the [handler] will be invoked
     * immediately with the latest value (if one has been broadcast), and then
     * again whenever a new value is emitted.
     *
     * The subscription is canceled and restarted when [stateKey] changes.
     *
     * ```kotlin
     * ListenFor<ThemeSettings> { settings ->
     *     update { it.copy(darkMode = settings.darkMode) }
     * }
     * ```
     *
     * @param O        the state type to listen for (inferred).
     * @param stateKey recomposition key; defaults to [Unit] (stable).
     *                 Change this to restart the subscription.
     * @param handler  invoked for each (intercepted) state value. Wrapped with
     *                 [rememberUpdatedState] for safe recomposition.
     */
    @Suppress("FunctionName")
    @Composable
    inline fun <reified O : Any> ListenFor(
        stateKey: Any? = Unit,
        noinline handler: (O) -> Unit,
    ) {
        val currentHandler by rememberUpdatedState(handler)
        DisposableEffect(this, stateKey) {
            val job = scope.launch {
                switchboard.stateFlow(O::class).collectLatest { currentHandler(it) }
            }
            onDispose { job.cancel() }
        }
    }
}
