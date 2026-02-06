import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("com.vanniktech.maven.publish") version "0.30.0"
    signing
    application
}

group = "com.synapselib"
version = "1.0.0"

dependencies {
    // Project "app" depends on project "utils". (Project paths are separated with ":", so ":utils" refers to the top-level "utils" project.)
    implementation(project(":utils"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "com.synapselib.app.AppKt"
}

mavenPublishing {
    // Tells the plugin to use the new Central Portal API
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Signs artifacts using GPG_SIGNING_KEY and GPG_PASSWORD env variables automatically
    signAllPublications()

    coordinates(group.toString(), "synapse-lib", version.toString())

    pom {
        name.set("SynapseLib")
        description.set("Kotlin library for high-performance synapses.")
        url.set("https://github.com/coreywaldon/synapse")
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