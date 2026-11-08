plugins {
    id("synapse.publish")
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.ksp)
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
    // com.synapselib:arch-processor (:arch-processor).

    // Run Dagger's processor over arch itself so the jar ships DefaultSwitchBoard_Factory
    // (as 1.0.x did). Without it, consumers' Dagger generates the factory itself and warns
    // "Prefer to run the dagger processor over that class", which breaks -Werror builds.
    // Build-time only: dagger-compiler is a `ksp` dependency and the dagger runtime is
    // compileOnly — apps that inject DefaultSwitchBoard already have Dagger, and apps that
    // don't never load the factory class.
    ksp(libs.daggerCompiler)
    compileOnly(libs.dagger)

    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(libs.junitJupiter)

    // The kotlinx.serialization compiler plugin, for the TEST compilation only: the state-persistence
    // tests declare `@Serializable` Node state classes exactly as an app would. arch's own (published)
    // classes declare nothing `@Serializable`, so the main compilation deliberately stays plugin-free
    // and its bytecode — notably the inline `rememberState` / `serializerOrNull` that consumers
    // inline — is unchanged. (Applying the Gradle plugin would enable it for main as well.)
    kotlinCompilerPluginClasspathTest(libs.kotlinSerializationCompilerPlugin)
}
