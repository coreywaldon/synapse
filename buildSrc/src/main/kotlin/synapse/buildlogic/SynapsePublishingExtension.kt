package synapse.buildlogic

import org.gradle.api.provider.Property

/**
 * Per-module publishing metadata, registered as `synapsePublishing { }` by the
 * `synapse.publish` convention plugin.
 *
 * Everything else about publishing — coordinates, licence, developers, SCM,
 * the Central Portal target and signing — is shared and lives in the
 * convention plugin. The Maven `artifactId` is always the Gradle project name
 * (`core`, `arch`, `arch-hilt`, `arch-test`, `arch-processor`).
 *
 * ```kotlin
 * plugins {
 *     id("synapse.publish")
 * }
 *
 * synapsePublishing {
 *     displayName = "Synapse Arch"
 *     description = "Synapse Architecture"
 * }
 * ```
 */
abstract class SynapsePublishingExtension {

    /** Human-readable POM `<name>`. Defaults to the project name. */
    abstract val displayName: Property<String>

    /** POM `<description>`. Required by Maven Central. */
    abstract val description: Property<String>
}
