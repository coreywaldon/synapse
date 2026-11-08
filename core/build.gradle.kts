plugins {
    id("synapse.publish")
}

synapsePublishing {
    displayName = "SynapseLib"
    description = "Kotlin library for high-performance synapses."
}

dependencies {
    // Flow / StateFlow appear throughout core's public API.
    api(libs.kotlinxCoroutinesCore)
    implementation(libs.atomicfu)

    testImplementation(libs.kotlinxCoroutinesTest)
    testImplementation(libs.junitJupiter)
}
