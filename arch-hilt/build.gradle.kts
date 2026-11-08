plugins {
    id("synapse.publish")
}

synapsePublishing {
    displayName = "Synapse Arch Hilt"
    description = "Hilt integration for the Synapse Architecture framework"
}

dependencies {
    api(project(":arch"))

    implementation(libs.hiltCore)
}
