package com.synapselib.arch.test

import androidx.lifecycle.Lifecycle
import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.SwitchBoardConfig
import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * DSL for configuring a [SynapseTestHarness] — and therefore a
 * [SynapseTestRule] or [SynapseExtension] — before each test.
 *
 * ```kotlin
 * @get:Rule
 * val synapse = SynapseTestRule {
 *     provide<List<Address>, FetchAddresses> { testAddresses }
 *     provide<AuthToken, FetchCachedToken> { null } // emit nothing
 *     include(ProductionProviders.registry)          // real providers
 *     switchBoard { maxConcurrentJobs = 2 }          // SwitchBoardConfig tweaks
 *     emitterTag = "Test"                            // trace test emissions
 *     initialLifecycleState = Lifecycle.State.CREATED
 * }
 * ```
 *
 * The block runs once per [SynapseTestHarness.start], i.e. lazily at the
 * beginning of every test — so it may reference fields of the test class that
 * are initialised after the rule.
 */
class SynapseTestConfig {

    @PublishedApi
    internal val builder: ProviderRegistry.Builder = ProviderRegistry.Builder(name = "SynapseTestConfig")

    private val boardCustomizers = ArrayList<SwitchBoardConfig.Builder.() -> Unit>()

    /**
     * Optional emitter tag for the harness's own [SynapseTestHarness.Trigger],
     * [SynapseTestHarness.Broadcast] and [SynapseTestHarness.Request] calls.
     * When non-null, test emissions run inside a fresh
     * [com.synapselib.arch.base.TraceContext] with this `emitterTag`, exactly
     * like a tagged coordinator. Default: `null` (untraced).
     */
    var emitterTag: String? = null

    /**
     * The state [SynapseTestHarness.lifecycleOwner] is moved to when the
     * harness starts. Default: [Lifecycle.State.RESUMED]. Use
     * [Lifecycle.State.INITIALIZED] or [Lifecycle.State.CREATED] to drive the
     * lifecycle yourself with [SynapseTestHarness.moveTo].
     *
     * Must not be [Lifecycle.State.DESTROYED].
     */
    var initialLifecycleState: Lifecycle.State = Lifecycle.State.RESUMED

    // ── Providers ───────────────────────────────────────────────────────

    /**
     * Registers an inline provider that emits a single value.
     *
     * If the [block] returns `null`, the provider emits nothing (empty flow),
     * which is useful for stubbing providers that should not produce data.
     *
     * ```kotlin
     * provide<List<Address>, FetchAddresses> { testAddresses }
     * provide<AuthToken, FetchCachedToken> { null } // no cached token
     * ```
     *
     * @param I    the [DataImpulse] subclass.
     * @param Need the result type the provider produces.
     * @param block a function that receives the impulse and returns the data to emit,
     *              or `null` to emit nothing.
     * @throws IllegalStateException if a provider for [I] is already registered.
     */
    inline fun <reified Need : Any, reified I : DataImpulse<Need>> provide(
        crossinline block: (I) -> Need?,
    ) {
        builder.register<Need, I> {
            object : Provider<I, Need>() {
                override fun ProviderScope.produce(impulse: I): Flow<Need> = flow {
                    val result = block(impulse)
                    if (result != null) emit(result)
                }
            }
        }
    }

    /**
     * Registers an inline provider that returns a custom [Flow].
     *
     * Use this when you need to emit multiple values, simulate delays,
     * or model streaming data sources.
     *
     * ```kotlin
     * provideFlow<List<Product>, FetchProducts> { impulse ->
     *     flow {
     *         emit(cachedProducts)
     *         delay(100)
     *         emit(freshProducts)
     *     }
     * }
     * ```
     *
     * @throws IllegalStateException if a provider for [I] is already registered.
     */
    inline fun <reified Need : Any, reified I : DataImpulse<Need>> provideFlow(
        crossinline block: (I) -> Flow<Need>,
    ) {
        builder.register<Need, I> {
            object : Provider<I, Need>() {
                override fun ProviderScope.produce(impulse: I): Flow<Need> = block(impulse)
            }
        }
    }

    /**
     * Adds every provider of an existing [registry] (e.g. a production or
     * KSP-generated registry) to the test board, alongside the inline
     * [provide] / [provideFlow] stubs.
     *
     * ```kotlin
     * SynapseTestRule {
     *     include(UserProviders.registry(FakeUserApi()))
     *     provide<Cart, FetchCart> { emptyCart }
     * }
     * ```
     *
     * @throws IllegalStateException if [registry] registers an impulse type
     *         that is already registered (duplicates are never silently overridden).
     */
    fun include(registry: ProviderRegistry) {
        builder.mergeFrom(registry)
    }

    // ── SwitchBoard ─────────────────────────────────────────────────────

    /**
     * Customises the [SwitchBoardConfig] of the test board. The block is
     * applied **after** the harness defaults (`workerContext = testDispatcher`,
     * `stopTimeout = stateTimeout`, `replayExpiration = impulseTimeout`), so it
     * can override any of them. May be called several times; blocks apply in
     * order.
     *
     * ```kotlin
     * SynapseTestRule {
     *     switchBoard {
     *         eagerSharing()
     *         maxConcurrentJobs = 1
     *         providerIdleTimeout = Duration.INFINITE
     *     }
     * }
     * ```
     */
    fun switchBoard(block: SwitchBoardConfig.Builder.() -> Unit) {
        boardCustomizers += block
    }

    // ── Internal ────────────────────────────────────────────────────────

    @PublishedApi
    internal fun buildRegistry(): ProviderRegistry = builder.build()

    internal fun applyBoardCustomizers(target: SwitchBoardConfig.Builder) {
        boardCustomizers.forEach { target.it() }
    }
}
