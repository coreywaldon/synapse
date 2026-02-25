plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
    id("com.google.devtools.ksp") version "2.3.5"
    application
}

group = "com.synapselib"
version = "1.0.1"

dependencies {
    implementation(project(":core"))
    testImplementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("org.jetbrains.compose.runtime:runtime:1.10.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.10.1")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    implementation("com.google.dagger:dagger-compiler:2.51.1")
    ksp("com.google.dagger:dagger-compiler:2.51.1")
    implementation("com.google.auto.service:auto-service-annotations:1.1.1")
    ksp("com.google.auto.service:auto-service:1.1.1")
}