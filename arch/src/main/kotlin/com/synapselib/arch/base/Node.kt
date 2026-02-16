package com.synapselib.arch.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
 * Impulses are emitted via [NodeScope.Trigger] or [CoordinatorScope.Trigger]
 * and consumed via [NodeScope.ReactTo] or [CoordinatorScope.ReactTo].
 *
 * Unlike state (which replays the latest value), impulses have **no replay** —
 * only active listeners receive them.
 */
open class Impulse

/**
 * Top-level composable that establishes a **context boundary** for the
 * SynapseLib DSL.
 *
 * A context pairs an arbitrary value [context] (typically a ViewModel, config
 * object, or domain model) with the nearest [SwitchBoard] from the composition,
 * wrapping them in a [ContextScope] that child [Node]s can access.
 *
 * ### Usage
 *
 * ```kotlin
 * @Composable
 * fun MyFeature(viewModel: MyViewModel) {
 *     CreateContext(viewModel) {
 *         // `this` is ContextScope<MyViewModel>
 *         Node(initialState = UiState()) {
 *             // `this` is NodeScope<MyViewModel, UiState>
 *             // context == viewModel
 *         }
 *     }
 * }
 * ```
 *
 * The [ContextScope] is `remember`ed by [context] and the [SwitchBoard],
 * so it is recreated only when either of those identities changes.
 *
 * @param T       the type of the context value.
 * @param context the value made available to all child scopes via
 *                [ContextScope.context].
 * @param block   composable content that runs inside the [ContextScope].
 */
@Composable
inline fun <T> CreateContext(
    context: T,
    crossinline block: @Composable ContextScope<T>.() -> Unit,
) {
    val switchboard = LocalSwitchBoard.current
    val scope = remember(context, switchboard) { ContextScope(context, switchboard) }
    scope.block()
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
 * @param T           the type of the context value.
 * @property context     the domain object shared across all [Node]s in this
 *                       context boundary (e.g., a ViewModel).
 * @property switchboard the [SwitchBoard] used for all cross-node communication
 *                       (state broadcasts, reactions, requests, interception).
 */
class ContextScope<T>(
    val context: T,
    val switchboard: SwitchBoard,
) {
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
     * Upstream reaction interceptors are applied before emission.
     *
     * @param A     the [Impulse] subtype (inferred).
     * @param event the impulse event to emit.
     */
    suspend inline fun <reified A : Impulse> Trigger(event: A) {
        switchboard.triggerImpulse(event)
    }

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
     * Upstream state interceptors are applied before emission.
     *
     * @param O    the state type (inferred).
     * @param data the value to broadcast.
     */
    suspend inline fun <reified O : Any> Broadcast(data: O) {
        switchboard.broadcastState(data)
    }
}

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
 * which unregisters any interceptors added via [NodeScope.Intercept].
 *
 * ### Usage
 *
 * ```kotlin
 * CreateContext(viewModel) {
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
    val stateHolder = remember { mutableStateOf(initialState) }
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
 * | **Interception** | [Intercept] | No — registers immediately |
 * | **Request** | [Request] | Yes — lifecycle-aware |
 * | **Listen (state)** | [ListenFor] | Yes — lifecycle-aware |
 * | **Listen (reaction)** | [ReactTo] | Yes — lifecycle-aware |
 *
 * ### Lifecycle
 *
 * The scope is created when the [Node] enters the composition and
 * [disposed][dispose] when it leaves. Disposal:
 * 1. Unregisters all interceptors added via [Intercept].
 * 2. Clears the internal registration list.
 *
 * Long-lived listeners ([ListenFor], [ReactTo], [Request]) are managed via
 * [DisposableEffect] and canceled automatically when their keys change or
 * the composable is removed.
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
) {
    val context: C get() = contextScope.context
    val state: S get() = stateHolder.value

    /** The [SwitchBoard] inherited from the enclosing [ContextScope]. */
    @PublishedApi
    internal val switchboard: SwitchBoard get() = contextScope.switchboard

    /**
     * Accumulated interceptor registrations that will be
     * [unregistered][Registration.unregister] on [dispose].
     */
    @PublishedApi
    internal val registrations = mutableListOf<Registration>()

    /**
     * Tears down this scope by unregistering all interceptors.
     *
     * Called automatically by the [DisposableEffect] inside [Node] when the
     * node leaves the composition. Should not normally be called manually.
     */
    @PublishedApi
    internal fun dispose() {
        registrations.forEach { it.unregister() }
        registrations.clear()
    }

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

    // ── Interceptor Registration (lifecycle-aware) ──────────────────────

    /**
     * Registers an [interceptor] at the given [point] for data of type [T],
     * automatically unregistering it when this node is disposed.
     *
     * ```kotlin
     * Node(initialState = MyState()) {
     *     Intercept<NetworkRequest>(
     *         point = InterceptPoint(Channel.REQUEST, Direction.UPSTREAM),
     *         interceptor = Interceptor.transform { req -> req.copy(token = authToken) },
     *     )
     * }
     * ```
     *
     * @param T           the data type the interceptor operates on.
     * @param point       the [InterceptPoint] (channel and direction) to intercept.
     * @param interceptor the [Interceptor] to install.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     */
    inline fun <reified T : Any> Intercept(
        point: InterceptPoint,
        interceptor: Interceptor<T>,
        priority: Int = 0,
    ) {
        val registration = switchboard.addInterceptor(point, T::class, interceptor, priority)
        registrations.add(registration)
    }

    // ── Imperative Actions (NOT @Composable) ────────────────────────────
    // Call these from callbacks, event handlers, or inside LaunchedEffect.

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
     * Upstream state interceptors are applied before emission.
     *
     * @param O    the state type (inferred).
     * @param data the value to broadcast.
     */
    suspend inline fun <reified O : Any> Broadcast(data: O) {
        contextScope.Broadcast(data)
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
     * Upstream reaction interceptors are applied before emission.
     *
     * @param A     the [Impulse] subtype (inferred).
     * @param event the impulse event to emit.
     */
    suspend inline fun <reified A : Impulse> Trigger(event: A) {
        contextScope.Trigger(event)
    }

    // ── Side Effects (lifecycle-aware, @Composable) ─────────────────────

    /**
     * Launches a request through the [SwitchBoard] and delivers each result
     * to [callback] by collecting the returned [kotlinx.coroutines.flow.SharedFlow].
     *
     * The request is canceled and relaunched whenever [key] changes, and
     * canceled when this composable leaves the composition.
     *
     * ```kotlin
     * Request<UserProfile, FetchUserParams>(
     *     params = FetchUserParams(userId = 42),
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
    @Composable
    inline fun <reified Need : Any, reified I : DataImpulse<Need>> Request(
        impulse: I,
        key: Any? = impulse,
        noinline callback: (DataState<Need>) -> Unit,
    ) {
        val currentCallback by rememberUpdatedState(callback)
        DisposableEffect(this, key) {
            val stateFlow = switchboard.handleRequest(I::class, Need::class, impulse)
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