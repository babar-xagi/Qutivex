package dev.qutivex.engine.manifest

import dev.qutivex.core.manifest.ManifestApplication
import dev.qutivex.core.manifest.ManifestProject
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.manifest.ManifestToolchain
import org.tomlj.Toml
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class ManifestParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ManifestParser {
    fun parse(path: Path): ManifestSpec {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.exists(normalized)) {
            throw NoSuchFileException(normalized.toString(), null, "Manifest file does not exist: '$normalized'.")
        }
        val content = Files.readString(normalized, UTF_8)
        return parse(content, normalized.fileName.toString())
    }

    fun parse(tomlContent: String, sourceName: String = "qutivex.toml"): ManifestSpec {
        val result = Toml.parse(tomlContent)
        if (result.hasErrors()) {
            val first = result.errors().first()
            val pos = first.position()
            throw ManifestParseException("Failed to parse $sourceName at ${pos.line()}:${pos.column()}: ${first.message}")
        }

        val allowedTopLevel = setOf(
            "schema-version",
            "project",
            "toolchain",
            "application",
            "dependencies",
            "test-dependencies",
        )
        for (key in result.keySet()) {
            if (key !in allowedTopLevel) {
                val pos = result.inputPositionOf(key)
                val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
                throw ManifestParseException("Unknown top-level field '$key'$posStr in $sourceName.")
            }
        }

        if (!result.contains("schema-version")) {
            throw ManifestParseException("Missing required field 'schema-version' in $sourceName.")
        }
        if (!result.isLong("schema-version")) {
            val pos = result.inputPositionOf("schema-version")
            val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
            throw ManifestParseException("Field 'schema-version' must be an integer$posStr in $sourceName.")
        }
        val schemaVersion = result.getLong("schema-version")!!.toInt()
        if (schemaVersion != 1) {
            throw ManifestParseException("Unsupported schema-version: $schemaVersion in $sourceName. Only schema-version 1 is supported.")
        }

        if (!result.contains("project")) {
            throw ManifestParseException("Missing required table [project] in $sourceName.")
        }
        if (!result.isTable("project")) {
            throw ManifestParseException("Field 'project' must be a table in $sourceName.")
        }
        val projectTable = result.getTable("project")!!
        val allowedProjectKeys = setOf("name", "version")
        for (key in projectTable.keySet()) {
            if (key !in allowedProjectKeys) {
                val pos = projectTable.inputPositionOf(key)
                val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
                throw ManifestParseException("Unknown field 'project.$key'$posStr in $sourceName.")
            }
        }
        if (!projectTable.contains("name")) {
            throw ManifestParseException("Missing required field 'name' in [project] in $sourceName.")
        }
        if (!projectTable.isString("name")) {
            val pos = projectTable.inputPositionOf("name")
            val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
            throw ManifestParseException("Field 'project.name' must be a string$posStr in $sourceName.")
        }
        val projectName = projectTable.getString("name")!!
        val projectVersion = if (projectTable.contains("version")) {
            if (!projectTable.isString("version")) {
                val pos = projectTable.inputPositionOf("version")
                val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
                throw ManifestParseException("Field 'project.version' must be a string$posStr in $sourceName.")
            }
            projectTable.getString("version")!!
        } else {
            "0.1.0"
        }

        if (!result.contains("toolchain")) {
            throw ManifestParseException("Missing required table [toolchain] in $sourceName.")
        }
        if (!result.isTable("toolchain")) {
            throw ManifestParseException("Field 'toolchain' must be a table in $sourceName.")
        }
        val toolchainTable = result.getTable("toolchain")!!
        val allowedToolchainKeys = setOf("kotlin", "jvm")
        for (key in toolchainTable.keySet()) {
            if (key !in allowedToolchainKeys) {
                val pos = toolchainTable.inputPositionOf(key)
                val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
                throw ManifestParseException("Unknown field 'toolchain.$key'$posStr in $sourceName.")
            }
        }
        if (!toolchainTable.contains("kotlin")) {
            throw ManifestParseException("Missing required field 'kotlin' in [toolchain] in $sourceName.")
        }
        if (!toolchainTable.isString("kotlin")) {
            val pos = toolchainTable.inputPositionOf("kotlin")
            val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
            throw ManifestParseException("Field 'toolchain.kotlin' must be a string$posStr in $sourceName.")
        }
        val kotlinVersion = toolchainTable.getString("kotlin")!!

        if (!toolchainTable.contains("jvm")) {
            throw ManifestParseException("Missing required field 'jvm' in [toolchain] in $sourceName.")
        }
        if (!toolchainTable.isLong("jvm")) {
            val pos = toolchainTable.inputPositionOf("jvm")
            val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
            throw ManifestParseException("Field 'toolchain.jvm' must be an integer$posStr in $sourceName.")
        }
        val jvmVersion = toolchainTable.getLong("jvm")!!.toInt()

        if (!result.contains("application")) {
            throw ManifestParseException("Missing required table [application] in $sourceName.")
        }
        if (!result.isTable("application")) {
            throw ManifestParseException("Field 'application' must be a table in $sourceName.")
        }
        val appTable = result.getTable("application")!!
        val allowedAppKeys = setOf("main-class")
        for (key in appTable.keySet()) {
            if (key !in allowedAppKeys) {
                val pos = appTable.inputPositionOf(key)
                val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
                throw ManifestParseException("Unknown field 'application.$key'$posStr in $sourceName.")
            }
        }
        if (!appTable.contains("main-class")) {
            throw ManifestParseException("Missing required field 'main-class' in [application] in $sourceName.")
        }
        if (!appTable.isString("main-class")) {
            val pos = appTable.inputPositionOf("main-class")
            val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
            throw ManifestParseException("Field 'application.main-class' must be a string$posStr in $sourceName.")
        }
        val mainClass = appTable.getString("main-class")!!

        val dependencies = parseDependencyTable(result, "dependencies", sourceName)
        val testDependencies = parseDependencyTable(result, "test-dependencies", sourceName)

        return try {
            ManifestSpec(
                schemaVersion = schemaVersion,
                project = ManifestProject(name = projectName, version = projectVersion),
                toolchain = ManifestToolchain(kotlin = kotlinVersion, jvm = jvmVersion),
                application = ManifestApplication(mainClass = mainClass),
                dependencies = dependencies,
                testDependencies = testDependencies,
            )
        } catch (e: IllegalArgumentException) {
            throw ManifestParseException("Invalid manifest in $sourceName: ${e.message}", e)
        }
    }

    private fun parseDependencyTable(
        result: org.tomlj.TomlParseResult,
        tableName: String,
        sourceName: String,
    ): Map<String, String> {
        if (!result.contains(tableName)) return emptyMap()
        if (!result.isTable(tableName)) {
            throw ManifestParseException("Field '$tableName' must be a table in $sourceName.")
        }
        val table = result.getTable(tableName)!!
        val map = mutableMapOf<String, String>()
        for (key in table.keySet()) {
            val keyPath = listOf(key)
            if (!table.isString(keyPath)) {
                val pos = table.inputPositionOf(keyPath)
                val posStr = if (pos != null) " at ${pos.line()}:${pos.column()}" else ""
                throw ManifestParseException("Dependency '$key' version must be a string$posStr in $sourceName.")
            }
            map[key] = table.getString(keyPath)!!
        }
        return map
    }
}
