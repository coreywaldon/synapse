tasks.register("updateReadmeVersions") {
    description = "Replaces hardcoded version strings in README.md files with synapse.version from gradle.properties"

    val version = providers.gradleProperty("synapse.version")
    val projectDir = layout.projectDirectory

    inputs.property("version", version)

    doLast {
        val v = version.get()
        val pattern = Regex("""(com\.synapselib:[\w-]+:)\d+\.\d+\.\d+""")

        listOf("README.md", "arch/README.md", "arch-hilt/README.md", "arch-test/README.md").forEach { path ->
            val file = projectDir.file(path).asFile
            if (file.exists()) {
                val original = file.readText()
                val updated = pattern.replace(original) { "${it.groupValues[1]}$v" }
                if (updated != original) {
                    file.writeText(updated)
                    logger.lifecycle("Updated $path → $v")
                } else {
                    logger.lifecycle("$path already up to date")
                }
            }
        }
    }
}
