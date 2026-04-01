package com.synapselib.arch.test

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.synapselib.arch.base.Channel
import com.synapselib.arch.base.Coordinator
import com.synapselib.arch.base.CoordinatorScope
import com.synapselib.arch.base.DefaultSwitchBoard
import com.synapselib.arch.base.Direction
import com.synapselib.arch.base.Impulse
import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.Interceptor
import com.synapselib.arch.base.Registration
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.arch.base.addInterceptor
import com.synapselib.arch.base.broadcastState
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.triggerImpulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A JUnit 4 [TestRule] that sets up a [SwitchBoard] with test dispatcher,
 * interceptor-based capture helpers, and automatic cleanup.
 *
 * ## Basic usage
 *
 * ```kotlin
 * @get:Rule
 * val synapse = SynapseTestRule()
 *
 * @Test
 * fun loginTriggersAuthSuccess() = synapse.runTest {
 *     val success = synapse.onImpulse<AuthSuccess>()
 *     synapse.fire(LoginRequested("user@test.com", "pass"))
 *     success.assertCaptured()
 * }
 * ```
 *
 * ## With providers
 *
 * ```kotlin
 * @get:Rule
 * val synapse = SynapseTestRule {
 *     provide<FetchAddresses, List<Address>> { testAddresses }
 * }
 * ```
 *
 * @param stateTimeout timeout for state channel sharing, in milliseconds.
 * @param impulseTimeout timeout for impulse channel sharing, in milliseconds.
 * @param configure optional DSL block to register providers via [SynapseTestConfig].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SynapseTestRule(
    private val stateTimeout: Long = 3_000,
    private val impulseTimeout: Long = 3_000,
    private val configure: (SynapseTestConfig.() -> Unit)? = null,
) : TestRule {

    /** The test dispatcher used for all coroutine execution. */
    val testDispatcher = UnconfinedTestDispatcher()

    /** The [SwitchBoard] instance for this test. Available after the rule starts. */
    lateinit var switchBoard: DefaultSwitchBoard
        private set

    /**
     * A [LifecycleOwner] in the [Lifecycle.State.RESUMED] state.
     * Use this when constructing coordinators that require a lifecycle owner.
     */
    lateinit var lifecycleOwner: LifecycleOwner
        private set

    private lateinit var lifecycleRegistry: LifecycleRegistry
    private lateinit var scope: CoroutineScope
    @PublishedApi
    internal val registrations = mutableListOf<Registration>()
    private val coordinators = mutableListOf<CoordinatorScope>()

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                setUp()
                try {
                    base.evaluate()
                } finally {
                    tearDown()
                }
            }
        }

    private fun setUp() {
        Dispatchers.setMain(testDispatcher)
        scope = CoroutineScope(SupervisorJob() + testDispatcher)

        // Build provider registry from DSL config
        val registry = if (configure != null) {
            val config = SynapseTestConfig()
            config.configure()
            config.buildRegistry()
        } else {
            ProviderRegistry.EMPTY
        }

        switchBoard = DefaultSwitchBoard(scope, registry, testDispatcher, stateTimeout, impulseTimeout)

        // Set up lifecycle owner
        lifecycleOwner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = lifecycleRegistry
        }
        lifecycleRegistry = LifecycleRegistry(lifecycleOwner)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    private fun tearDown() {
        coordinators.forEach { it.dispose() }
        coordinators.clear()
        registrations.forEach { it.unregister() }
        registrations.clear()
        Dispatchers.resetMain()
    }

    // ── Interceptor Capture Helpers ──────────────────────────────────────

    /**
     * Registers an upstream interceptor on the **reaction** channel that captures
     * the most recent impulse of type [T].
     *
     * ```kotlin
     * val checkout = synapse.onImpulse<CheckoutRequested>()
     * // ... trigger the impulse ...
     * assertEquals("addr-1", checkout.assertCaptured().addressId)
     * ```
     */
    inline fun <reified T : Any> onImpulse(): Capture<T> {
        var captured: T? = null
        registrations += switchBoard.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.UPSTREAM),
            interceptor = Interceptor.read<T> { captured = it },
            priority = Int.MAX_VALUE,
        )
        return Capture { captured }
    }

    /**
     * Registers an upstream interceptor on the **state** channel that captures
     * the most recent state broadcast of type [T].
     *
     * ```kotlin
     * val session = synapse.onState<SessionState>()
     * // ... trigger login ...
     * val auth = session.assertCaptured() as SessionState.Authenticated
     * ```
     */
    inline fun <reified T : Any> onState(): Capture<T> {
        var captured: T? = null
        registrations += switchBoard.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            interceptor = Interceptor.read<T> { captured = it },
            priority = Int.MAX_VALUE,
        )
        return Capture { captured }
    }

    /**
     * Registers an upstream interceptor on the **reaction** channel that collects
     * **all** impulses of type [T], in order.
     *
     * ```kotlin
     * val toasts = synapse.onAllImpulses<ShowToast>()
     * // ... perform multiple actions ...
     * toasts.assertCount(2)
     * assertEquals("Added to cart", toasts.values[0].message)
     * ```
     */
    inline fun <reified T : Any> onAllImpulses(): CaptureAll<T> {
        val list = mutableListOf<T>()
        registrations += switchBoard.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.UPSTREAM),
            interceptor = Interceptor.read<T> { list.add(it) },
            priority = Int.MAX_VALUE,
        )
        return CaptureAll(list)
    }

    /**
     * Registers an upstream interceptor on the **state** channel that collects
     * **all** state broadcasts of type [T], in order.
     */
    inline fun <reified T : Any> onAllStates(): CaptureAll<T> {
        val list = mutableListOf<T>()
        registrations += switchBoard.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            interceptor = Interceptor.read<T> { list.add(it) },
            priority = Int.MAX_VALUE,
        )
        return CaptureAll(list)
    }

    // ── Fire / Broadcast ────────────────────────────────────────────────

    /**
     * Fires an impulse into the SwitchBoard's reaction channel.
     *
     * ```kotlin
     * synapse.fire(OrderPlaced())
     * synapse.fire(AuthError("Invalid credentials"))
     * ```
     */
    suspend inline fun <reified T : Impulse> Trigger(impulse: T) {
        switchBoard.triggerImpulse(impulse)
    }

    /**
     * Broadcasts a state value into the SwitchBoard's state channel.
     *
     * ```kotlin
     * synapse.broadcast(SessionState.LoggedOut)
     * ```
     */
    suspend inline fun <reified T : Any> Broadcast(state: T) {
        switchBoard.broadcastState(state)
    }

    // ── Coordinator Lifecycle ───────────────────────────────────────────

    /**
     * Initializes a [CoordinatorScope] connected to this rule's [switchBoard]
     * and [lifecycleOwner]. The coordinator is automatically disposed on teardown.
     *
     * ```kotlin
     * val coordinator = synapse.coordinator { switchBoard, owner ->
     *     AuthCoordinator(authApi, owner).also { it.initialize(switchBoard) }
     * }
     * ```
     */
    fun Coordinator(
        tag: String? = null,
        block: CoordinatorScope.() -> Unit,
    ): CoordinatorScope {
        val scope = Coordinator(switchBoard, lifecycleOwner, tag, block)
        coordinators += scope
        return scope
    }

    // ── Test Runner ─────────────────────────────────────────────────────

    /**
     * Convenience wrapper around [kotlinx.coroutines.test.runTest] that
     * automatically uses this rule's [testDispatcher].
     *
     * ```kotlin
     * @Test
     * fun myTest() = synapse.runTest {
     *     val success = synapse.onImpulse<AuthSuccess>()
     *     synapse.fire(LoginRequested("user@test.com", "pass"))
     *     success.assertCaptured()
     * }
     * ```
     */
    fun runTest(block: suspend TestScope.() -> Unit) {
        kotlinx.coroutines.test.runTest(testDispatcher) { block() }
    }
}
