package dev.qutivex.core.manifest

import dev.qutivex.core.project.ProjectSpec

/** The manifest specification representing schema-version = 1. */
data class ManifestSpec(
    val schemaVersion: Int = 1,
    val project: ManifestProject,
    val toolchain: ManifestToolchain = ManifestToolchain(),
    val application: ManifestApplication = ManifestApplication(),
    val dependencies: Map<String, String> = emptyMap(),
    val testDependencies: Map<String, String> = emptyMap(),
) {
    init {
        require(schemaVersion == 1) {
            "Unsupported schema-version: $schemaVersion. Only schema-version 1 is supported."
        }
        for ((coordinate, version) in dependencies) {
            validateCoordinate(coordinate)
            validateVersion(coordinate, version)
        }
        for ((coordinate, version) in testDependencies) {
            validateCoordinate(coordinate)
            validateVersion(coordinate, version)
        }
    }

    fun toProjectSpec(): ProjectSpec = ProjectSpec(
        name = project.name,
        kotlinVersion = toolchain.kotlin,
        jvmVersion = toolchain.jvm,
        version = project.version,
        mainClass = application.mainClass,
    )

    fun withDependency(coordinate: String, version: String): ManifestSpec {
        val updated = dependencies.toMutableMap()
        updated[coordinate] = version
        return copy(dependencies = updated)
    }

    fun withoutDependency(coordinate: String): ManifestSpec {
        val updated = dependencies.toMutableMap()
        updated.remove(coordinate)
        return copy(dependencies = updated)
    }

    fun withTestDependency(coordinate: String, version: String): ManifestSpec {
        val updated = testDependencies.toMutableMap()
        updated[coordinate] = version
        return copy(testDependencies = updated)
    }

    fun withoutTestDependency(coordinate: String): ManifestSpec {
        val updated = testDependencies.toMutableMap()
        updated.remove(coordinate)
        return copy(testDependencies = updated)
    }

    fun toToml(): String = buildString {
        append("schema-version = $schemaVersion\n\n")
        append("[project]\n")
        append("name = \"${project.name}\"\n")
        append("version = \"${project.version}\"\n\n")
        append("[toolchain]\n")
        append("kotlin = \"${toolchain.kotlin}\"\n")
        append("jvm = ${toolchain.jvm}\n\n")
        append("[application]\n")
        append("main-class = \"${application.mainClass}\"\n\n")
        append("[dependencies]\n")
        for ((coord, ver) in dependencies.toSortedMap()) {
            append("\"$coord\" = \"$ver\"\n")
        }
        append("\n[test-dependencies]\n")
        for ((coord, ver) in testDependencies.toSortedMap()) {
            append("\"$coord\" = \"$ver\"\n")
        }
        append("\n")
    }

    fun computeHash(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = toToml().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val COORDINATE_PATTERN = Regex("^[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+$")

        fun validateCoordinate(coordinate: String) {
            require(COORDINATE_PATTERN.matches(coordinate)) {
                "Invalid dependency coordinate '$coordinate'. Expected format 'group:artifact'."
            }
        }

        fun validateVersion(coordinate: String, version: String) {
            require(version.isNotBlank()) {
                "Dependency '$coordinate' version must not be blank."
            }
        }

        fun fromProjectSpec(project: ProjectSpec): ManifestSpec = ManifestSpec(
            schemaVersion = 1,
            project = ManifestProject(name = project.name, version = project.version),
            toolchain = ManifestToolchain(kotlin = project.kotlinVersion, jvm = project.jvmVersion),
            application = ManifestApplication(mainClass = project.mainClass),
        )
    }
}

data class ManifestProject(
    val name: String,
    val version: String = "0.1.0",
) {
    init {
        ProjectSpec(name = name, version = version)
        require(version.isNotBlank()) { "Project version must not be blank." }
    }
}

data class ManifestToolchain(
    val kotlin: String = "2.4.10",
    val jvm: Int = 21,
) {
    init {
        require(kotlin.isNotBlank()) { "Toolchain kotlin version must not be blank." }
        require(jvm >= 8) { "Toolchain JVM version must be at least 8: $jvm." }
    }
}

data class ManifestApplication(
    val mainClass: String = "MainKt",
) {
    init {
        require(mainClass.isNotBlank()) { "Application main-class must not be blank." }
        require(!mainClass.contains(Regex("\\s"))) { "Application main-class must not contain whitespace: '$mainClass'." }
    }
}
