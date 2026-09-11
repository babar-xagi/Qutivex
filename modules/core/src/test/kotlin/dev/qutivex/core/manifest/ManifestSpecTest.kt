package dev.qutivex.core.manifest

import dev.qutivex.core.project.ProjectSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManifestSpecTest {
    @Test
    fun `creates valid manifest and converts to project spec`() {
        val manifest = ManifestSpec(
            schemaVersion = 1,
            project = ManifestProject("my-app", "1.0.0"),
            toolchain = ManifestToolchain("2.4.10", 21),
            application = ManifestApplication("com.example.AppKt"),
            dependencies = mapOf("org.jetbrains.kotlinx:kotlinx-coroutines-core" to "1.10.2"),
            testDependencies = mapOf("org.junit.jupiter:junit-jupiter" to "5.10.1"),
        )

        val project = manifest.toProjectSpec()
        assertEquals("my-app", project.name)
        assertEquals("1.0.0", project.version)
        assertEquals("2.4.10", project.kotlinVersion)
        assertEquals(21, project.jvmVersion)
        assertEquals("com.example.AppKt", project.mainClass)
    }

    @Test
    fun `rejects unsupported schema version`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestSpec(
                schemaVersion = 2,
                project = ManifestProject("my-app"),
            )
        }
    }

    @Test
    fun `rejects invalid dependency coordinate format`() {
        val invalidCoordinates = listOf("invalid", "group:", ":artifact", "group/artifact", "group:artifact@1.0")
        for (coord in invalidCoordinates) {
            assertFailsWith<IllegalArgumentException>("Expected '$coord' to be rejected") {
                ManifestSpec(
                    schemaVersion = 1,
                    project = ManifestProject("my-app"),
                    dependencies = mapOf(coord to "1.0.0"),
                )
            }
        }
    }

    @Test
    fun `rejects blank dependency version`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestSpec(
                schemaVersion = 1,
                project = ManifestProject("my-app"),
                dependencies = mapOf("group:artifact" to "  "),
            )
        }
    }

    @Test
    fun `rejects invalid project name in manifest`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestProject("Invalid_Capital")
        }
    }

    @Test
    fun `creates ManifestSpec from ProjectSpec`() {
        val project = ProjectSpec("my-app")
        val manifest = ManifestSpec.fromProjectSpec(project)

        assertEquals("my-app", manifest.project.name)
        assertEquals("0.1.0", manifest.project.version)
        assertEquals("2.4.10", manifest.toolchain.kotlin)
        assertEquals(21, manifest.toolchain.jvm)
        assertEquals("MainKt", manifest.application.mainClass)
    }
}
