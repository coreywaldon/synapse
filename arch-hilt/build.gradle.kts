import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("com.vanniktech.maven.publish")
    signing
}

group = property("synapse.group") as String
version = property("synapse.version") as String

dependencies {
    api(project(":arch"))

    implementation("com.google.dagger:hilt-core:2.51.1")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates(group.toString(), "arch-hilt", version.toString())

    pom {
        name.set("Synapse Arch Hilt")
        description.set("Hilt integration for the Synapse Architecture framework")
        url.set("https://github.com/coreywaldon/synapse/arch-hilt")
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
