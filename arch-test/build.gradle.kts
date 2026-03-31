import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("com.vanniktech.maven.publish")
    signing
}

group = "com.synapselib"
version = "1.0.5"

dependencies {
    api(project(":arch"))
    api(project(":core"))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    api("junit:junit:4.13.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.0")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), "arch-test", version.toString())

    pom {
        name.set("Synapse Arch Test")
        description.set("Testing utilities for the Synapse Architecture framework")
        url.set("https://github.com/coreywaldon/synapse/arch-test")
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
