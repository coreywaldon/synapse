package com.synapselib.arch.base.provider

/**
 * A single module's contribution to the merged [ProviderRegistry].
 *
 * Each Gradle module annotated with `@SynapseProvider` classes generates a
 * Hilt `@IntoSet` binding that provides one [ProviderRegistryContribution].
 * The [SynapseProviderAggregatorModule] collects all contributions and
 * merges them into a single [ProviderRegistry] singleton.
 *
 * Build the [registry] with a named [ProviderRegistry.Builder] (e.g.
 * `ProviderRegistry.Builder(name = "feature-cart")`) so that, if two modules
 * register a provider for the same impulse type, the duplicate-registration
 * error names both modules.
 *
 * @property registry the partial registry built from one module's providers.
 */
class ProviderRegistryContribution(
    val registry: ProviderRegistry,
) {
    /** The contributing module's name, as given to its [ProviderRegistry.Builder]. */
    val name: String? get() = registry.name

    override fun toString(): String = "ProviderRegistryContribution($registry)"
}
