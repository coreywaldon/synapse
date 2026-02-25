import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
    id("com.google.devtools.ksp") version "2.3.5"
    id("com.vanniktech.maven.publish")
    signing
    application
}

group = "com.synapselib"
version = "1.0.2"

dependencies {
    implementation(project(":core"))
    testImplementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("org.jetbrains.compose.runtime:runtime:1.10.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.10.1")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    implementation("com.google.dagger:dagger-compiler:2.51.1")
    ksp("com.google.dagger:dagger-compiler:2.51.1")
    implementation("com.google.auto.service:auto-service-annotations:1.1.1")
    ksp("com.google.auto.service:auto-service:1.1.1")
}

mavenPublishing {
    // Tells the plugin to use the new Central Portal API
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Signs artifacts using GPG_SIGNING_KEY and GPG_PASSWORD env variables automatically
    signAllPublications()

    coordinates(group.toString(), "arch", version.toString())

    pom {
        name.set("Synapse Arch")
        description.set("Synapse Architecture")
        url.set("https://github.com/coreywaldon/synapse/arch")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
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

signing {
    val signingKey = System.getenv("GPG_SIGNING_KEY")
    val signingPassword = System.getenv("GPG_PASSWORD")
    useInMemoryPgpKeys(signingKey, signingPassword)
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}