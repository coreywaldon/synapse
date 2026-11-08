plugins {
    id("synapse.publish")
    // DataState is `@Serializable(with = DataStateSerializer::class)`. The plugin is what generates
    // `DataState.Companion.serializer(typeSerial0)`, so runtime lookups such as
    // `serializer(typeOf<DataState<Item>>())` resolve too — not only plugin-compiled consumer
    // properties. It also compiles the `@Serializable` fixtures in core's tests.
    alias(libs.plugins.kotlinSerialization)
}

synapsePublishing {
    displayName = "SynapseLib"
    description = "Kotlin library for high-performance synapses."
}

dependencies {
    // Flow / StateFlow appear throughout core's public API.
    api(libs.kotlinxCoroutinesCore)
    // @Serializable / KSerializer appear in core's public API (DataState, DataStateSerializer),
    // and consumers' serialization plugin needs the runtime to compile classes holding a DataState.
    api(libs.kotlinxSerializationCore)
    implementation(libs.atomicfu)

    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(libs.kotlinxSerializationJson)
    testImplementation(libs.junitJupiter)
}
