package com.synapselib.arch.hilt

import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderRegistryContribution
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that aggregates all [ProviderRegistryContribution]s from
 * across Gradle modules into a single [ProviderRegistry].
 *
 * Each module's KSP-generated code contributes a [ProviderRegistryContribution]
 * via `@IntoSet`. This module collects the full set and merges them.
 *
 * If two modules register a provider for the same `DataImpulse` type,
 * [ProviderRegistry.Builder.mergeFrom] will throw an [IllegalStateException]
 * at app startup with a clear duplicate message.
 */
@Module
@InstallIn(SingletonComponent::class)
object SynapseProviderAggregatorModule {

    @Provides
    @Singleton
    fun provideRegistry(
        contributions: Set<@JvmSuppressWildcards ProviderRegistryContribution>,
    ): ProviderRegistry {
        val builder = ProviderRegistry.Builder()
        for (contribution in contributions) {
            builder.mergeFrom(contribution.registry)
        }
        return builder.build()
    }
}
