package com.synapselib.arch.base.provider

/**
 * A single module's contribution to the merged [ProviderRegistry].
 *
 * Each Gradle module annotated with `@SynapseProvider` classes generates a
 * Hilt `@IntoSet` binding that provides one [ProviderRegistryContribution].
 * The [SynapseProviderAggregatorModule] collects all contributions and
 * merges them into a single [ProviderRegistry] singleton.
 *
 * @property registry the partial registry built from one module's providers.
 */
class ProviderRegistryContribution(
    val registry: ProviderRegistry,
)
