plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("com.google.devtools.ksp") version "2.3.5"
    application
}

group = "com.synapselib"
version = "1.0.1"

dependencies {
    implementation(project(":app"))
    testImplementation(project(":app"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // AndroidX Compose
    implementation("androidx.compose.runtime:runtime:1.7.8")
    implementation("androidx.compose.foundation:foundation:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.2")

    testImplementation("androidx.compose.runtime:runtime:1.7.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    implementation("com.google.dagger:dagger-compiler:2.51.1")
    ksp("com.google.dagger:dagger-compiler:2.51.1")
    implementation("com.google.auto.service:auto-service-annotations:1.1.1")
    ksp("com.google.auto.service:auto-service:1.1.1")
}