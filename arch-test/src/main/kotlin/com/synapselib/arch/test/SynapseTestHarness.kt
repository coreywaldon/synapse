package com.synapselib.arch.test

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.synapselib.arch.base.CoordinatorScope
import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.DefaultSwitchBoard
import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.arch.base.SwitchBoardConfig
import com.synapselib.arch.base.SynapseScope
import com.synapselib.arch.base.provider.ProviderRegistry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.Collections
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

/**
 * Framework-agnostic test fixture for SynapseLib: a real [DefaultSwitchBoard]
 * driven by a test dispatcher, a controllable [LifecycleOwner], thread-safe
 * capture helpers and deterministic teardown.
 *
 * [SynapseTestRule] (JUnit 4) and [SynapseExtension] (JUnit 5) are thin
 * adapters around this class; use it directly with any other test framework:
 *
 * ```kotlin
 * val synapse = SynapseTestHarness { provide<List<Item>, FetchItems> { items } }
 * synapse.start()
 * try {
 *     synapse.runTest {
 *         val ping = synapse.onImpulse<Ping>()
 *         synapse.Trigger(Ping())
 *         ping.assertCaptured()
 *     }
 * } finally {
 *     synapse.close()
 * }
 * // or: SynapseTestHarness().start().use { synapse -> … }
 * ```
 *
 * ## Shared verbs
 *
 * The harness **is a** [SynapseScope], so the same verbs used in production
 * code are available on it: [Broadcast], [Trigger], [ListenFor], [ReactTo]
 * and [Request]. Set [SynapseTestConfig.emitterTag] to trace test emissions.
 *
 * ## Lifecycle of the harness
 *
 * [start] creates, per test:
 * 1. a `CoroutineScope(SupervisorJob() + testDispatcher)` ([coroutineScope]);
 * 2. a [DefaultSwitchBoard] built from a [SwitchBoardConfig] with
 *    `workerContext = testDispatcher`, `stopTimeout = stateTimeout`,
 *    `replayExpiration = impulseTimeout`, then the
 *    [SynapseTestConfig.switchBoard] customisations;
 * 3. a [LifecycleOwner] moved to [SynapseTestConfig.initialLifecycleState]
 *    (default [Lifecycle.State.RESUMED]);
 * 4. `Dispatchers.setMain(testDispatcher)`.
 *
 * [close] (idempotent) then:
 * 1. disposes every coordinator created through the [Coordinator] helpers;
 * 2. moves the lifecycle owner to [Lifecycle.State.DESTROYED] (if it was at
 *    least `CREATED`), which auto-disposes coordinators you created yourself
 *    against [lifecycleOwner] and cancels its `lifecycleScope`;
 * 3. detaches every capture (captured values are kept);
 * 4. [closes][DefaultSwitchBoard.close] the board — cancelling provider jobs
 *    and sharing coroutines — and cancels [coroutineScope];
 * 5. resets `Dispatchers.Main`.
 *
 * Every step runs even if an earlier one fails; the first failure is rethrown
 * with the others suppressed.
 *
 * A closed harness may be [started][start] again; each start gets a fresh
 * board, scope and lifecycle owner. [testDispatcher] is created once per
 * harness and reused across starts.
 *
 * ## Threading
 *
 * Captures, coordinator tracking and [close] are thread-safe. The harness
 * replaces the **global** `Dispatchers.Main`, so two harnesses must not be
 * active at the same time (do not run Synapse tests in parallel within one
 * JVM).
 *
 * @param stateTimeout   [SwitchBoardConfig.stopTimeout] of the test board, in
 *                       milliseconds (how long downstream-intercepted flows
 *                       stay shared after their last subscriber leaves; also
 *                       the default provider idle timeout).
 * @param impulseTimeout [SwitchBoardConfig.replayExpiration] of the test board,
 *                       in milliseconds.
 * @param configure      optional DSL block run at every [start]; see [SynapseTestConfig].
 */
@OptIn(ExperimentalCoroutinesApi::class)
open class SynapseTestHarness(
    private val stateTimeout: Long = 3_000,
    private val impulseTimeout: Long = 3_000,
    private val configure: (SynapseTestConfig.() -> Unit)? = null,
) : SynapseScope(), AutoCloseable {

    /** The test dispatcher used for all coroutine execution (board, providers, `Main`). */
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()

    /** Everything created by one [start]; discarded by [close]. */
    private class Session(
        val scope: CoroutineScope,
        val board: DefaultSwitchBoard,
        val owner: TestLifecycleOwner,
        val tag: String?,
    )

    private class TestLifecycleOwner : LifecycleOwner {
        // createUnsafe: no main-thread enforcement, so tests may drive the
        // lifecycle (and dispose coordinators) from any thread.
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val lock = Any()

    @Volatile
    private var session: Session? = null

    private val captureHub = CaptureHub()

    private val coordinators: MutableList<CoordinatorScope> = Collections.synchronizedList(ArrayList())

    private fun requireSession(): Session = session ?: throw IllegalStateException(
        "${this::class.simpleName} is not started. It is started automatically by SynapseTestRule / " +
            "SynapseExtension before each test; when using SynapseTestHarness directly, call start() first."
    )

    // ── State ───────────────────────────────────────────────────────────

    /** `true` between [start] and [close]. */
    val isStarted: Boolean get() = session != null

    /**
     * The [DefaultSwitchBoard] for the current test.
     *
     * @throws IllegalStateException if the harness is not started.
     */
    val switchBoard: DefaultSwitchBoard get() = requireSession().board

    /**
     * [SynapseScope] view of [switchBoard], used by the inherited verbs.
     *
     * @throws IllegalStateException if the harness is not started.
     */
    final override val switchboard: SwitchBoard get() = switchBoard

    /** [SynapseTestConfig.emitterTag] of the current test, or `null`. */
    final override val tag: String? get() = session?.tag

    /** The configuration [switchBoard] was built with. */
    val switchBoardConfig: SwitchBoardConfig get() = switchBoard.config

    /**
     * The parent scope of [switchBoard] (`SupervisorJob() + testDispatcher`),
     * cancelled on [close]. Use it for lifecycle-free coordinators or other
     * background work that should end with the test.
     *
     * @throws IllegalStateException if the harness is not started.
     */
    val coroutineScope: CoroutineScope get() = requireSession().scope

    /**
     * A [LifecycleOwner] for coordinators, initially in
     * [SynapseTestConfig.initialLifecycleState] (default
     * [Lifecycle.State.RESUMED]). Drive it with [moveTo].
     *
     * @throws IllegalStateException if the harness is not started.
     */
    val lifecycleOwner: LifecycleOwner get() = requireSession().owner

    /**
     * The current state of [lifecycleOwner].
     *
     * @throws IllegalStateException if the harness is not started.
     */
    val lifecycleState: Lifecycle.State get() = requireSession().owner.registry.currentState

    // ── Start / Close ───────────────────────────────────────────────────

    /**
     * Creates the board, scope and lifecycle owner for a test and installs
     * [testDispatcher] as `Dispatchers.Main`. Runs the `configure` block.
     *
     * @return this harness, for chaining (`SynapseTestHarness().start().use { … }`).
     * @throws IllegalStateException if already started, or if the `configure`
     *         block registers duplicate providers.
     * @throws IllegalArgumentException if the resulting [SwitchBoardConfig] is
     *         invalid or [SynapseTestConfig.initialLifecycleState] is `DESTROYED`.
     */
    fun start(): SynapseTestHarness {
        synchronized(lock) {
            check(session == null) { "${this::class.simpleName} is already started; call close() first" }

            val config = SynapseTestConfig()
            configure?.invoke(config)
            require(config.initialLifecycleState != Lifecycle.State.DESTROYED) {
                "initialLifecycleState must not be DESTROYED"
            }
            val registry: ProviderRegistry = config.buildRegistry()
            val boardConfig = SwitchBoardConfig {
                workerContext = testDispatcher
                stopTimeout = stateTimeout.milliseconds
                replayExpiration = impulseTimeout.milliseconds
                config.applyBoardCustomizers(this)
            }

            val scope = CoroutineScope(SupervisorJob() + testDispatcher + CoroutineName("SynapseTestHarness"))
            val board = DefaultSwitchBoard(scope, registry, boardConfig)
            board.addObserver(captureHub)
            val owner = TestLifecycleOwner()
            owner.registry.currentState = config.initialLifecycleState

            Dispatchers.setMain(testDispatcher)
            session = Session(scope, board, owner, config.emitterTag)
        }
        return this
    }

    /**
     * Tears down the current test; see the class docs for the exact order.
     * Idempotent: does nothing if the harness is not started.
     *
     * @throws Throwable the first failure of any teardown step (e.g. an
     *         `onDispose` callback that throws), with later failures suppressed.
     */
    override fun close() {
        val current = synchronized(lock) { session.also { session = null } } ?: return
        val failures = ArrayList<Throwable>()
        fun attempt(step: () -> Unit) {
            try {
                step()
            } catch (t: Throwable) {
                failures += t
            }
        }

        val toDispose = synchronized(coordinators) { coordinators.toList().also { coordinators.clear() } }
        toDispose.forEach { attempt { it.dispose() } }
        attempt {
            val registry = current.owner.registry
            if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
        captureHub.clear()
        attempt { current.board.close() }
        attempt { current.scope.cancel() }
        attempt { Dispatchers.resetMain() }

        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { if (it !== first) first.addSuppressed(it) }
            throw first
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Moves [lifecycleOwner] to [state], synchronously dispatching every
     * intermediate event (e.g. `RESUMED → CREATED` dispatches `ON_PAUSE` then
     * `ON_STOP`) to coordinators and other observers.
     *
     * ```kotlin
     * synapse.Coordinator { onStop { stopped = true } }
     * synapse.moveTo(Lifecycle.State.CREATED)
     * assertTrue(stopped)
     * ```
     *
     * Moving to [Lifecycle.State.DESTROYED] auto-disposes lifecycle-bound
     * coordinators; `DESTROYED` is terminal.
     *
     * @throws IllegalStateException if the harness is not started, or if the
     *         transition is impossible (leaving `DESTROYED`, or
     *         `INITIALIZED → DESTROYED`).
     */
    fun moveTo(state: Lifecycle.State) {
        requireSession().owner.registry.currentState = state
    }

    // ── Capture Helpers ─────────────────────────────────────────────────
    //
    // Captures are fed by one SwitchBoardObserver (see CaptureHub): they see
    // the value after *all* interceptors of their point, never modify the
    // interceptor chain, and may be created before start() (e.g. as fields).

    /**
     * Captures the most recent value of type [T] (or a subtype) observed at
     * [point], after that point's interceptors have run.
     *
     * ```kotlin
     * val delivered = synapse.capture<Counter>(InterceptPoint.STATE_DOWNSTREAM)
     * ```
     *
     * Downstream state/reaction points only see values while something
     * collects the corresponding flow (e.g. a coordinator's `ListenFor`).
     */
    fun <T : Any> capture(point: InterceptPoint, type: KClass<T>): Capture<T> {
        val capture = Capture(point, type)
        captureHub.add(point, type, capture::record)
        return capture
    }

    /** Reified form of [capture]. */
    inline fun <reified T : Any> capture(point: InterceptPoint): Capture<T> = capture(point, T::class)

    /**
     * Collects **all** values of type [T] (or a subtype) observed at [point],
     * in order, after that point's interceptors have run.
     */
    fun <T : Any> captureAll(point: InterceptPoint, type: KClass<T>): CaptureAll<T> {
        val capture = CaptureAll(point, type)
        captureHub.add(point, type, capture::record)
        return capture
    }

    /** Reified form of [captureAll]. */
    inline fun <reified T : Any> captureAll(point: InterceptPoint): CaptureAll<T> = captureAll(point, T::class)

    /**
     * Collects **all** values of type [T] (or a subtype) that an interceptor
     * **blocked** at [point], in order — i.e. values for which no `proceed`
     * call reached the end of the chain, so they were not emitted, sent to a
     * provider or delivered. Each value is recorded as it entered the stage.
     *
     * Blocked values are never recorded by [capture] / [captureAll] or the
     * `on…` helpers, which only see dispatched values.
     */
    fun <T : Any> captureBlocked(point: InterceptPoint, type: KClass<T>): CaptureAll<T> {
        val capture = CaptureAll(point, type, blocked = true)
        captureHub.addBlocked(point, type, capture::record)
        return capture
    }

    /**
     * Reified form of [captureBlocked]: collects every [T] an interceptor
     * blocked at [point].
     *
     * ```kotlin
     * val gated = synapse.onBlocked<FetchProfile>(InterceptPoint.REQUEST_UPSTREAM)
     * val sent = synapse.onRequest<FetchProfile>()
     * // ... request a profile while logged out ...
     * gated.assertCount(1)
     * sent.assertNotCaptured() // never reached the provider
     * ```
     */
    inline fun <reified T : Any> onBlocked(point: InterceptPoint): CaptureAll<T> = captureBlocked(point, T::class)

    /**
     * Captures the most recent impulse of type [T] triggered on the
     * **reaction** channel (upstream, i.e. as emitted, after upstream
     * interceptors).
     *
     * ```kotlin
     * val checkout = synapse.onImpulse<CheckoutRequested>()
     * // ... trigger the impulse ...
     * assertEquals("addr-1", checkout.assertCaptured().addressId)
     * ```
     */
    inline fun <reified T : Any> onImpulse(): Capture<T> = capture(InterceptPoint.REACTION_UPSTREAM)

    /**
     * Collects **all** impulses of type [T] triggered on the reaction channel, in order.
     *
     * ```kotlin
     * val toasts = synapse.onAllImpulses<ShowToast>()
     * // ... perform multiple actions ...
     * toasts.assertCount(2)
     * assertEquals("Added to cart", toasts.values[0].message)
     * ```
     */
    inline fun <reified T : Any> onAllImpulses(): CaptureAll<T> = captureAll(InterceptPoint.REACTION_UPSTREAM)

    /**
     * Captures the most recent state of type [T] broadcast on the **state**
     * channel (upstream, after upstream interceptors).
     *
     * ```kotlin
     * val session = synapse.onState<SessionState>()
     * // ... trigger login ...
     * val auth = session.assertCaptured() as SessionState.Authenticated
     * ```
     */
    inline fun <reified T : Any> onState(): Capture<T> = capture(InterceptPoint.STATE_UPSTREAM)

    /** Collects **all** states of type [T] broadcast on the state channel, in order. */
    inline fun <reified T : Any> onAllStates(): CaptureAll<T> = captureAll(InterceptPoint.STATE_UPSTREAM)

    /**
     * Captures the most recent request impulse of type [I] sent through the
     * **request** channel (upstream, after upstream request interceptors —
     * i.e. the impulse the provider will actually receive).
     *
     * Recorded once per *collection* of a request flow, before provider
     * deduplication.
     *
     * ```kotlin
     * val fetch = synapse.onRequest<FetchAddresses>()
     * // ... screen requests addresses ...
     * assertEquals("user-1", fetch.assertCaptured().userId)
     * ```
     */
    inline fun <reified I : DataImpulse<*>> onRequest(): Capture<I> = capture(InterceptPoint.REQUEST_UPSTREAM)

    /** Collects **all** request impulses of type [I], in order; see [onRequest]. */
    inline fun <reified I : DataImpulse<*>> onAllRequests(): CaptureAll<I> = captureAll(InterceptPoint.REQUEST_UPSTREAM)

    /**
     * Captures the most recent request **result** of type [Need] delivered to
     * a requester (request channel, downstream, after downstream request
     * interceptors).
     *
     * Recorded for the data of every `DataState.Success` and for the
     * `staleData` of a `DataState.Error`, once per collecting requester.
     *
     * ```kotlin
     * val addresses = synapse.onRequestResult<List<Address>>()
     * ```
     */
    inline fun <reified Need : Any> onRequestResult(): Capture<Need> = capture(InterceptPoint.REQUEST_DOWNSTREAM)

    /** Collects **all** request results of type [Need], in order; see [onRequestResult]. */
    inline fun <reified Need : Any> onAllRequestResults(): CaptureAll<Need> =
        captureAll(InterceptPoint.REQUEST_DOWNSTREAM)

    // ── Coordinators ────────────────────────────────────────────────────

    /**
     * Creates a [CoordinatorScope] bound to [lifecycleOwner] and this test's
     * [switchBoard]. It is disposed automatically on [close].
     *
     * Lifecycle hooks registered in [block] for states the owner has already
     * reached fire right after [block] (with the default `RESUMED` owner:
     * `onCreate`, `onStart`, `onResume`, in that order).
     *
     * ```kotlin
     * synapse.Coordinator(tag = "Auth") {
     *     ReactTo<LoginRequested> { launch { Trigger(AuthSuccess()) } }
     * }
     * ```
     */
    @Suppress("FunctionName")
    fun Coordinator(
        tag: String? = null,
        block: CoordinatorScope.() -> Unit,
    ): CoordinatorScope = Coordinator(lifecycleOwner, tag, block)

    /**
     * Creates a [CoordinatorScope] bound to a custom [owner] and this test's
     * [switchBoard]. It is disposed automatically on [close].
     */
    @Suppress("FunctionName")
    fun Coordinator(
        owner: LifecycleOwner,
        tag: String? = null,
        block: CoordinatorScope.() -> Unit,
    ): CoordinatorScope = track(com.synapselib.arch.base.Coordinator(switchBoard, owner, tag, block))

    /**
     * Creates a **lifecycle-free** [CoordinatorScope] bound to [scope] (e.g.
     * [coroutineScope]) and this test's [switchBoard]. It is disposed
     * automatically on [close] (and when [scope] is cancelled).
     */
    @Suppress("FunctionName")
    fun Coordinator(
        scope: CoroutineScope,
        tag: String? = null,
        block: CoordinatorScope.() -> Unit,
    ): CoordinatorScope = track(com.synapselib.arch.base.Coordinator(switchBoard, scope, tag, block))

    private fun track(coordinator: CoordinatorScope): CoordinatorScope {
        coordinators += coordinator
        return coordinator
    }

    // ── Test Runner ─────────────────────────────────────────────────────

    /**
     * Convenience wrapper around [kotlinx.coroutines.test.runTest] that
     * uses this harness's [testDispatcher].
     *
     * ```kotlin
     * @Test
     * fun myTest() = synapse.runTest {
     *     val success = synapse.onImpulse<AuthSuccess>()
     *     synapse.Trigger(LoginRequested("user@test.com", "pass"))
     *     success.assertCaptured()
     * }
     * ```
     */
    fun runTest(block: suspend TestScope.() -> Unit) {
        kotlinx.coroutines.test.runTest(testDispatcher) { block() }
    }
}
