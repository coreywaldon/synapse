package com.synapselib.arch.base.provider

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP entry point for the `@[SynapseProvider]` annotation processor.
 *
 * Discovered via [AutoService] — no manual registration required.
 * Delegates all work to [ProviderProcessor].
 */
@AutoService(SymbolProcessorProvider::class)
class ProviderProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        // Fallback to "App" or "Default" if the argument wasn't provided
        val rawModuleName = environment.options["synapse.moduleName"] ?: "App"

        // Strip out hyphens or invalid characters from the Gradle module name
        // (e.g., "feature-login" -> "FeatureLogin")
        val safeModuleName = rawModuleName
            .split(Regex("[^a-zA-Z0-9]+"))
            .joinToString("") { word -> word.replaceFirstChar { it.uppercase() } }

        return ProviderProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            moduleName = safeModuleName
        )
    }
}
