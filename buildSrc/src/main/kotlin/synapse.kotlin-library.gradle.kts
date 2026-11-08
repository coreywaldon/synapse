// ── synapse.kotlin-library ──────────────────────────────────────────────
//
// Convention plugin shared by every Kotlin/JVM module in this build:
//  - applies the Kotlin JVM plugin (version pinned by buildSrc's catalog dependency),
//  - pins the JDK toolchain to 21,
//  - sets `group` / `version` from `synapse.group` / `synapse.version` in gradle.properties,
//  - runs tests on the JUnit Platform with per-test logging.
//
// Modules that are published to Maven Central should apply `synapse.publish` instead,
// which applies this plugin itself.

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
}

group = providers.gradleProperty("synapse.group").get()
version = providers.gradleProperty("synapse.version").get()

kotlin {
    // Use a specific Java version to make it easier to work in different environments.
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}
