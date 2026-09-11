package dev.qutivex.engine.manifest

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManifestParserTest {
    @TempDir
    lateinit var tempDir: Path

    private val parser = ManifestParser()

    @Test
    fun `parses standard initialized manifest`() {
        val toml = """
            schema-version = 1

            [project]
            name = "hello"
            version = "0.1.0"

            [toolchain]
            kotlin = "2.4.10"
            jvm = 21

            [application]
            main-class = "MainKt"

            [dependencies]

            [test-dependencies]
        """.trimIndent()

        val manifest = parser.parse(toml)
        assertEquals(1, manifest.schemaVersion)
        assertEquals("hello", manifest.project.name)
        assertEquals("0.1.0", manifest.project.version)
        assertEquals("2.4.10", manifest.toolchain.kotlin)
        assertEquals(21, manifest.toolchain.jvm)
        assertEquals("MainKt", manifest.application.mainClass)
        assertTrue(manifest.dependencies.isEmpty())
        assertTrue(manifest.testDependencies.isEmpty())
    }

    @Test
    fun `parses manifest with dependencies and test dependencies`() {
        val toml = """
            schema-version = 1

            [project]
            name = "my-app"
            version = "1.2.3"

            [toolchain]
            kotlin = "2.4.10"
            jvm = 21

            [application]
            main-class = "dev.qutivex.sample.AppKt"

            [dependencies]
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.10.2"

            [test-dependencies]
            "org.junit.jupiter:junit-jupiter" = "5.10.1"
        """.trimIndent()

        val manifest = parser.parse(toml)
        assertEquals(mapOf("org.jetbrains.kotlinx:kotlinx-coroutines-core" to "1.10.2"), manifest.dependencies)
        assertEquals(mapOf("org.junit.jupiter:junit-jupiter" to "5.10.1"), manifest.testDependencies)
    }

    @Test
    fun `parses from file path`() {
        val file = tempDir.resolve("qutivex.toml")
        Files.writeString(
            file,
            """
                schema-version = 1
                [project]
                name = "path-test"
                [toolchain]
                kotlin = "2.4.10"
                jvm = 21
                [application]
                main-class = "MainKt"
            """.trimIndent(),
        )

        val manifest = parser.parse(file)
        assertEquals("path-test", manifest.project.name)
    }

    @Test
    fun `fails on missing file`() {
        assertFailsWith<NoSuchFileException> {
            parser.parse(tempDir.resolve("non-existent.toml"))
        }
    }

    @Test
    fun `rejects syntax errors with line context`() {
        val toml = "schema-version = 1\n[project\nname = 'test'"
        val exception = assertFailsWith<ManifestParseException> {
            parser.parse(toml)
        }
        assertTrue(exception.message!!.contains("at 2:"), "Expected line context in: ${exception.message}")
    }

    @Test
    fun `rejects duplicate keys`() {
        val toml = """
            schema-version = 1
            schema-version = 1
            [project]
            name = "dup"
            [toolchain]
            kotlin = "2.4.10"
            jvm = 21
            [application]
            main-class = "MainKt"
        """.trimIndent()
        assertFailsWith<ManifestParseException> {
            parser.parse(toml)
        }
    }

    @Test
    fun `rejects unknown top-level fields`() {
        val toml = """
            schema-version = 1
            unknown-key = "bad"
            [project]
            name = "unknown-test"
            [toolchain]
            kotlin = "2.4.10"
            jvm = 21
            [application]
            main-class = "MainKt"
        """.trimIndent()
        val ex = assertFailsWith<ManifestParseException> {
            parser.parse(toml)
        }
        assertTrue(ex.message!!.contains("Unknown top-level field 'unknown-key'"))
    }

    @Test
    fun `rejects unknown table fields`() {
        val toml = """
            schema-version = 1
            [project]
            name = "unknown-field"
            extra = "not-allowed"
            [toolchain]
            kotlin = "2.4.10"
            jvm = 21
            [application]
            main-class = "MainKt"
        """.trimIndent()
        val ex = assertFailsWith<ManifestParseException> {
            parser.parse(toml)
        }
        assertTrue(ex.message!!.contains("Unknown field 'project.extra'"))
    }

    @Test
    fun `rejects missing schema-version`() {
        val toml = """
            [project]
            name = "no-schema"
            [toolchain]
            kotlin = "2.4.10"
            jvm = 21
            [application]
            main-class = "MainKt"
        """.trimIndent()
        val ex = assertFailsWith<ManifestParseException> {
            parser.parse(toml)
        }
        assertTrue(ex.message!!.contains("Missing required field 'schema-version'"))
    }

    @Test
    fun `rejects unsupported schema-version`() {
        val toml = """
            schema-version = 2
            [project]
            name = "v2"
            [toolchain]
            kotlin = "2.4.10"
            jvm = 21
            [application]
            main-class = "MainKt"
        """.trimIndent()
        val ex = assertFailsWith<ManifestParseException> {
            parser.parse(toml)
        }
        assertTrue(ex.message!!.contains("Unsupported schema-version: 2"))
    }

    @Test
    fun `rejects wrong data types in fields`() {
        val toml = """
            schema-version = "one"
            [project]
            name = "type-err"
            [toolchain]
            kotlin = "2.4.10"
            jvm = 21
            [application]
            main-class = "MainKt"
        """.trimIndent()
        val ex = assertFailsWith<ManifestParseException> {
            parser.parse(toml)
        }
        assertTrue(ex.message!!.contains("Field 'schema-version' must be an integer"))
    }

    @Test
    fun `rejects non-string dependency version`() {
        val toml = """
            schema-version = 1
            [project]
            name = "dep-type"
            [toolchain]
            kotlin = "2.4.10"
            jvm = 21
            [application]
            main-class = "MainKt"
            [dependencies]
            "org.example:lib" = 123
        """.trimIndent()
        val ex = assertFailsWith<ManifestParseException> {
            parser.parse(toml)
        }
        assertTrue(ex.message!!.contains("version must be a string"))
    }
}
