plugins {
    id("synapse.publish")
}

synapsePublishing {
    displayName = "Synapse Arch Test"
    description = "Testing utilities for the Synapse Architecture framework"
}

dependencies {
    api(project(":arch"))
    api(project(":core"))

    api(libs.kotlinxCoroutinesTest)
    api(libs.junit4)

    // SynapseExtension (JUnit 5) is compiled against the Jupiter API but does not force it on
    // JUnit 4 consumers; Jupiter users already have junit-jupiter-api on their test classpath.
    compileOnly(libs.junitJupiterApi)

    implementation(libs.kotlinxCoroutinesCore)
    implementation(libs.lifecycleCommon)
    implementation(libs.lifecycleRuntime)

    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitVintageEngine)
}
