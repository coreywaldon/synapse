plugins {
    // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
    // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
    // and are applied in the project's `build.gradle.kts` files as required.
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Plugins applied by the convention plugins must be on buildSrc's classpath.
    // Versions come from the shared catalog (gradle/libs.versions.toml).
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.vanniktechPublishPlugin)
}
