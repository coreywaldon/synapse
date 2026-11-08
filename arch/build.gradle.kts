plugins {
    id("synapse.publish")
    alias(libs.plugins.kotlinCompose)
}

synapsePublishing {
    displayName = "Synapse Arch"
    description = "Synapse Architecture"
}

dependencies {
    // Types from these appear in arch's public API (DataState, Flow, @Composable,
    // LifecycleOwner, @Inject/@Qualifier), so consumers get them on their compile classpath.
    api(project(":core"))
    api(libs.kotlinxCoroutinesCore)
    api(libs.composeRuntime)
    api(libs.lifecycleCommon)
    api(libs.javaxInject)

    implementation(libs.composeRuntimeSaveable)
    implementation(libs.lifecycleRuntime)
    implementation(libs.kotlinxSerializationJson)

    // The @SynapseProvider KSP processor is published separately as
    // com.synapselib:arch-processor (:arch-processor). Nothing compile-only belongs here.

    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(libs.junitJupiter)
}
