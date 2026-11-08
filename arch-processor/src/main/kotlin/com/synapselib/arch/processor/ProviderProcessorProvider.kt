package com.synapselib.arch.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP entry point for the `@SynapseProvider` annotation processor.
 *
 * Registered with KSP through the service file
 * `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
 * shipped in the `com.synapselib:arch-processor` artifact. Apply it with
 * `ksp("com.synapselib:arch-processor:<version>")`.
 *
 * Delegates all work to [ProviderProcessor].
 *
 * ## Options
 *
 * - `synapse.moduleName` — suffix for the generated module name, unique per
 *   Gradle module (e.g. `"feature-login"` → `SynapseProviderModule_FeatureLogin`).
 *   Defaults to `"App"`.
 */
class ProviderProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        // Fallback to "App" if the argument wasn't provided
        val rawModuleName = environment.options[MODULE_NAME_OPTION] ?: DEFAULT_MODULE_NAME

        return ProviderProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
            moduleName = sanitizeModuleName(rawModuleName),
        )
    }

    companion object {
        /** KSP option key: `ksp { arg("synapse.moduleName", "FeatureAuth") }`. */
        const val MODULE_NAME_OPTION: String = "synapse.moduleName"

        /** Module name used when [MODULE_NAME_OPTION] is not supplied. */
        const val DEFAULT_MODULE_NAME: String = "App"

        /**
         * Turns a Gradle-style module name into a valid, PascalCase identifier
         * suffix by stripping hyphens and other invalid characters
         * (e.g. `"feature-login"` → `"FeatureLogin"`).
         */
        fun sanitizeModuleName(raw: String): String = raw
            .split(Regex("[^a-zA-Z0-9]+"))
            .joinToString("") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
