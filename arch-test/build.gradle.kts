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

    implementation(libs.kotlinxCoroutinesCore)
    implementation(libs.lifecycleCommon)
    implementation(libs.lifecycleRuntime)

    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitVintageEngine)
}
