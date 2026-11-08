// ── synapse.publish ─────────────────────────────────────────────────────
//
// Convention plugin for every module published to Maven Central as
// `com.synapselib:<project name>:<synapse.version>`.
//
// A module build file only declares its POM name/description and its dependencies:
//
//     plugins { id("synapse.publish") }
//     synapsePublishing {
//         displayName = "Synapse Arch"
//         description = "Synapse Architecture"
//     }
//
// Publishing goes through the vanniktech plugin to the Sonatype Central Portal
// (`./gradlew publishToMavenCentral`, credentials from the
// `mavenCentralUsername` / `mavenCentralPassword` Gradle properties).
//
// Signing is configured ONLY when the `GPG_SIGNING_KEY` environment variable is
// set (optionally with `GPG_PASSWORD`). Without it, no signing tasks are created,
// so `./gradlew publishToMavenLocal` works on any machine without GPG keys.

import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.plugins.signing.SigningExtension
import synapse.buildlogic.SynapsePublishingExtension

plugins {
    id("synapse.kotlin-library")
    id("com.vanniktech.maven.publish")
}

val synapsePublishing: SynapsePublishingExtension =
    extensions.create<SynapsePublishingExtension>("synapsePublishing").apply {
        displayName.convention(project.name)
    }

// ── Coordinates & POM ──

mavenPublishing {
    // Tells the plugin to use the Central Portal API.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    coordinates(project.group.toString(), project.name, project.version.toString())

    pom {
        name.set(synapsePublishing.displayName)
        description.set(synapsePublishing.description)
        url.set("https://github.com/coreywaldon/synapse")
        // Must match the repository's LICENSE file (MPL 2.0). The pre-1.1.0 per-module
        // build files declared Apache-2.0 here by mistake.
        licenses {
            license {
                name.set("Mozilla Public License 2.0")
                url.set("https://www.mozilla.org/en-US/MPL/2.0/")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("coreywaldon")
                name.set("Corey Waldon")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/coreywaldon/synapse.git")
            url.set("https://github.com/coreywaldon/synapse")
        }
    }
}

// ── Signing (only when a key is available) ──

val signingKey: String? = providers.environmentVariable("GPG_SIGNING_KEY").orNull
if (!signingKey.isNullOrBlank()) {
    val signingPassword: String? = providers.environmentVariable("GPG_PASSWORD").orNull

    // Applies the `signing` plugin and signs every publication.
    mavenPublishing.signAllPublications()

    extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
}
