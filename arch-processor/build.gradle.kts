plugins {
    id("synapse.publish")
}

synapsePublishing {
    displayName = "Synapse Arch Processor"
    description = "KSP processor that generates Hilt / Koin provider registries for @SynapseProvider classes"
}

dependencies {
    // The processor emits generated code as strings and references arch types by
    // fully-qualified name, so it needs nothing but the KSP API at compile time.
    // KSP supplies the API at processing time, hence compileOnly.
    compileOnly(libs.kspApi)

    // End-to-end tests: run the processor in-process via kotlin-compile-testing (KSP2)
    // and compile the generated module against the real arch / Hilt annotations.
    testImplementation(libs.kspApi)
    testImplementation(libs.kctforkCore)
    testImplementation(libs.kctforkKsp)
    testImplementation(project(":arch"))
    testImplementation(libs.hiltCore)
    testImplementation(libs.junitJupiter)
}

// kotlin-compile-testing pins an older KSP; run the tests against the catalog's KSP version
// (symbol-processing, -common-deps, -aa-embeddable) so they exercise what users actually get.
configurations.matching { it.name.startsWith("test") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.google.devtools.ksp") {
            useVersion(libs.versions.ksp.get())
            because("align all KSP artifacts with the catalog version")
        }
    }
}
