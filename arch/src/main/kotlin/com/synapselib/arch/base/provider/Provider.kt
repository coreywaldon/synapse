package com.synapselib.arch.base.provider

import com.synapselib.arch.base.DataImpulse
import com.synapselib.core.typed.DataState
import com.synapselib.arch.base.Impulse
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.arch.base.broadcastState
import com.synapselib.arch.base.triggerImpulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * A cold-start data producer that handles a specific [com.synapselib.arch.base.DataImpulse] type.
 *
 * Providers are the data-fetching backbone of the SynapseLib architecture.
 * They replace the old Router/Producer system with a design that participates
 * in the [com.synapselib.arch.base.SwitchBoard] event system natively rather than bypassing it.
 *
 * ## Lifecycle
 *
 * Providers are **factory-instantiated** — they don't exist until a matching
 * [com.synapselib.arch.base.DataImpulse] arrives. The [ProviderManager] creates them on demand,
 * collects their output, wraps it in [DataState], and disposes them when
 * the work is complete or all subscribers have disconnected.
 *
 * ## SwitchBoard Access
 *
 * The [produce] function runs inside a [ProviderScope], giving full access
 * to the [com.synapselib.arch.base.SwitchBoard]: [ProviderScope.ListenFor], [ProviderScope.Trigger],
 * [ProviderScope.Broadcast], and even nested [ProviderScope.Request] calls.
 *
 * ## Implementation
 *
 * Return a [Flow] of [Need] values. For one-shot fetches, emit a single
 * value. For streaming/observing data, emit continuously.
 *
 * ```kotlin
 * // One-shot fetch
 * @Producer
 * class FetchUserProfileProvider : Provider<FetchUserProfile, UserProfile>() {
 *     @Inject lateinit var api: UserApi
 *
 *     override fun ProviderScope.produce(impulse: FetchUserProfile) = flow {
 *         emit(api.getProfile(impulse.userId))
 *     }
 * }
 *
 * // Streaming / observation
 * @Producer
 * class WatchCartProvider : Provider<WatchCart, Cart>() {
 *     @Inject lateinit var cartDao: CartDao
 *
 *     override fun ProviderScope.produce(impulse: WatchCart): Flow<Cart> =
 *         cartDao.observeCart(impulse.cartId)
 * }
 *
 * // Provider that uses other SwitchBoard capabilities
 * @Producer
 * class FetchAuthenticatedDataProvider : Provider<FetchSecureData, SecurePayload>() {
 *     @Inject lateinit var api: SecureApi
 *
 *     override fun ProviderScope.produce(impulse: FetchSecureData) = flow {
 *         val token = ListenFor<AuthToken>().first()
 *         emit(api.fetch(impulse.endpoint, token))
 *     }
 * }
 * ```
 *
 * @param I    the concrete [com.synapselib.arch.base.DataImpulse] subclass this provider handles.
 * @param Need the data type this provider produces (must match the impulse's
 *             type parameter).
 */
abstract class Provider<I : DataImpulse<Need>, Need : Any> {

    /**
     * Produces a [Flow] of [Need] values in response to the given [impulse].
     *
     * Runs inside a [ProviderScope] with full [com.synapselib.arch.base.SwitchBoard] access. The
     * framework automatically wraps emissions in [DataState]:
     * - [DataState.Loading] is emitted before [produce] is called.
     * - Each emitted value becomes [DataState.Success].
     * - Any uncaught exception becomes [DataState.Error].
     *
     * The flow's lifecycle is managed by the [ProviderManager] — when all
     * subscribers disconnect (after a configurable timeout), the flow is
     * canceled and the provider is disposed.
     *
     * @param impulse the data impulse containing request parameters.
     * @return a [Flow] emitting one or more [Need] values.
     */
    abstract fun ProviderScope.produce(impulse: I): Flow<Need>
}

/**
 * A lightweight, scoped context given to [Provider]s during execution.
 *
 * Similar to [com.synapselib.arch.base.CoordinatorScope] but with a framework-managed lifecycle.
 * The provider does not control when this scope is created or destroyed —
 * the [ProviderManager] handles that based on subscriber demand.
 *
 * Implements [CoroutineScope] by delegation, so standard coroutine builders
 * (`launch`, `async`) are available inside [Provider.produce]:
 *
 * ```kotlin
 * override fun ProviderScope.produce(impulse: MyImpulse) = flow {
 *     // Launch a side effect without blocking the flow
 *     launch { Trigger(AnalyticsEvent("data_fetched")) }
 *     emit(repository.getData())
 * }
 * ```
 *
 * @param switchboard    the [com.synapselib.arch.base.SwitchBoard] for cross-component communication.
 * @param coroutineScope the backing scope; canceled when the provider is disposed.
 */
class ProviderScope(
    val switchboard: SwitchBoard,
    coroutineScope: CoroutineScope,
) : CoroutineScope by coroutineScope {

    // ── State Observation ───────────────────────────────────────────────

    /**
     * Returns the downstream-intercepted [SharedFlow] for state of type [O].
     */
    inline fun <reified O : Any> ListenFor(): SharedFlow<O> =
        switchboard.stateFlow(O::class)

    /**
     * Returns the downstream-intercepted [SharedFlow] for impulses of type [A].
     */
    inline fun <reified A : Impulse> ReactTo(): SharedFlow<A> =
        switchboard.impulseFlow(A::class)

    // ── Imperative Actions ──────────────────────────────────────────────

    /**
     * Broadcasts [data] into the [SwitchBoard]'s state channel.
     */
    suspend inline fun <reified O : Any> Broadcast(data: O) {
        switchboard.broadcastState(data)
    }

    /**
     * Triggers a fire-and-forget [Impulse] through the [SwitchBoard].
     */
    suspend inline fun <reified A : Impulse> Trigger(event: A) {
        switchboard.triggerImpulse(event)
    }

    // ── Nested Requests ─────────────────────────────────────────────────

    /**
     * Issues a nested data request through the [SwitchBoard].
     *
     * Allows providers to depend on other providers' data. The returned
     * flow carries the full [DataState] lifecycle of the nested request.
     */
    inline fun <reified Need : Any, reified I : DataImpulse<Need>> Request(
        impulse: I,
    ): SharedFlow<DataState<Need>> =
        switchboard.handleRequest(I::class, Need::class, impulse)
}

/**
 * Factory function type for creating [Provider] instances on demand.
 *
 * The [ProviderManager] holds these factories and invokes them when a
 * matching [DataImpulse] arrives for the first time (or after a previous
 * provider has been disposed).
 *
 * In a DI-managed project, KSP generates the factory registrations
 * automatically from `@Producer`-annotated classes.
 *
 * @param I    the [DataImpulse] subclass the provider handles.
 * @param Need the data type the provider produces.
 */
fun interface ProviderFactory<I : DataImpulse<Need>, Need : Any> {
    fun create(): Provider<I, Need>
}

/**
 * Marks a concrete [Provider] subclass for
 * compile-time validation and automatic [ProviderRegistry]
 * wiring.
 *
 * ## Requirements
 *
 * The annotated class must:
 * 1. Be a **concrete** (non-abstract) class.
 * 2. Directly extend [Provider]`<I, Need>` with
 *    concrete type arguments (no raw types, no type variables).
 * 3. Have an `@Inject` constructor (for Hilt) or be resolvable by Koin.
 *
 * ## What the KSP Processor Does
 *
 * **Validation (compile errors):**
 * - Every `@SynapseProvider` class must extend `Provider<I, Need>`.
 * - No two `@SynapseProvider` classes may handle the same [DataImpulse] type.
 * - Type arguments must be concrete, resolvable types.
 *
 * **Code generation:**
 * - A Hilt `@Module` that provides a [ProviderRegistry] singleton,
 *   with each provider wired as a cold-start factory via
 *   `javax.inject.Provider<T>`.
 * - A Koin module equivalent.
 *
 * ## Example
 *
 * ```kotlin
 * data class FetchUserProfile(val userId: Int) : DataImpulse<UserProfile>()
 *
 * @SynapseProvider
 * class FetchUserProfileProvider @Inject constructor(
 *     private val api: UserApi,
 * ) : Provider<FetchUserProfile, UserProfile>() {
 *
 *     override fun ProviderScope.produce(impulse: FetchUserProfile) = flow {
 *         emit(api.getProfile(impulse.userId))
 *     }
 * }
 * ```
 *
 * The processor generates:
 * ```kotlin
 * // Hilt
 * @Module @InstallIn(SingletonComponent::class)
 * object SynapseProviderModule {
 *     @Provides @Singleton
 *     fun provideRegistry(
 *         fetchUserProfileProvider: javax.inject.Provider<FetchUserProfileProvider>,
 *     ): ProviderRegistry = ProviderRegistry.Builder()
 *         .register(FetchUserProfile::class, UserProfile::class.java,
 *             ProviderFactory { fetchUserProfileProvider.get() })
 *         .build()
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class SynapseProvider
