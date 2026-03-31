package com.synapselib.arch.test

import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * DSL builder for configuring providers in a [SynapseTestRule].
 *
 * ```kotlin
 * @get:Rule
 * val synapse = SynapseTestRule {
 *     provide<FetchAddresses, List<Address>> { testAddresses }
 *     provide<FetchCachedToken, AuthToken> { null } // emit nothing
 * }
 * ```
 */
class SynapseTestConfig {

    @PublishedApi
    internal val builder = ProviderRegistry.Builder()

    /**
     * Registers an inline provider that emits a single value.
     *
     * If the [block] returns `null`, the provider emits nothing (empty flow),
     * which is useful for stubbing providers that should not produce data.
     *
     * ```kotlin
     * provide<FetchAddresses, List<Address>> { testAddresses }
     * provide<FetchCachedToken, AuthToken> { null } // no cached token
     * ```
     *
     * @param I    the [DataImpulse] subclass.
     * @param Need the result type the provider produces.
     * @param block a function that receives the impulse and returns the data to emit,
     *              or `null` to emit nothing.
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
     * provideFlow<FetchProducts, List<Product>> { impulse ->
     *     flow {
     *         emit(cachedProducts)
     *         delay(100)
     *         emit(freshProducts)
     *     }
     * }
     * ```
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

    @PublishedApi
    internal fun buildRegistry(): ProviderRegistry = builder.build()
}
