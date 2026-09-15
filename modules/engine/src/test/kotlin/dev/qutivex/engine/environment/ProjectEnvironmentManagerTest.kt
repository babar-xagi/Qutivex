package dev.qutivex.engine.environment

import dev.qutivex.core.manifest.ManifestProject
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.manifest.ManifestToolchain
import dev.qutivex.engine.toolchain.ToolchainManager
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectEnvironmentManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val toolchainManager by lazy {
        ToolchainManager(baseDir = tempDir.resolve("toolchains"))
    }

    private val envManager by lazy {
        ProjectEnvironmentManager(toolchainManager = toolchainManager)
    }

    @Test
    fun `ensureEnvironment creates all expected directories`() {
        val projectDir = tempDir.resolve("env-test-app")
        Files.createDirectories(projectDir)
        val manifest = ManifestSpec(
            project = ManifestProject(name = "env-test-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
        )
        Files.writeString(projectDir.resolve("qutivex.toml"), manifest.toToml())

        val qutivexDir = envManager.ensureEnvironment(projectDir, manifest)

        assertTrue(Files.exists(qutivexDir.resolve("env")))
        assertTrue(Files.exists(qutivexDir.resolve("build")))
        assertTrue(Files.exists(qutivexDir.resolve("classes/main")))
        assertTrue(Files.exists(qutivexDir.resolve("classes/test")))
        assertTrue(Files.exists(qutivexDir.resolve("state")))
        assertTrue(Files.exists(qutivexDir.resolve("cache")))

        assertTrue(Files.exists(qutivexDir.resolve("env/env.toml")))
        assertTrue(Files.exists(qutivexDir.resolve("state/project-state.json")))
    }

    @Test
    fun `info reports complete and accurate project environment metadata`() {
        val projectDir = tempDir.resolve("info-test-app")
        Files.createDirectories(projectDir)
        val manifest = ManifestSpec(
            project = ManifestProject(name = "info-test-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
            dependencies = mapOf("org.jetbrains:annotations" to "23.0.0"),
        )
        Files.writeString(projectDir.resolve("qutivex.toml"), manifest.toToml())

        val info = envManager.info(projectDir)
        assertEquals("info-test-app", info.projectName)
        assertEquals("2.4.10", info.kotlinVersion)
        assertEquals(21, info.jdkVersion)
        assertEquals(1, info.directDependencies)
        assertTrue(info.render().contains("Project Environment: info-test-app"))
    }

    @Test
    fun `clean removes ephemeral artifacts and resets status to CLEANED`() {
        val projectDir = tempDir.resolve("clean-test-app")
        Files.createDirectories(projectDir)
        val manifest = ManifestSpec(
            project = ManifestProject(name = "clean-test-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
        )
        Files.writeString(projectDir.resolve("qutivex.toml"), manifest.toToml())
        envManager.ensureEnvironment(projectDir, manifest)

        // Put a dummy class in classes/main
        val mainClass = projectDir.resolve(".qutivex/classes/main/Foo.class")
        Files.writeString(mainClass, "dummy")
        assertTrue(Files.exists(mainClass))

        val stdout = StringWriter()
        envManager.clean(projectDir, PrintWriter(stdout))

        assertFalse(Files.exists(mainClass))
        assertTrue(stdout.toString().contains("Cleaned project environment"))
    }

    @Test
    fun `recreate removes and reconstructs environment from scratch`() {
        val projectDir = tempDir.resolve("recreate-test-app")
        Files.createDirectories(projectDir)
        val manifest = ManifestSpec(
            project = ManifestProject(name = "recreate-test-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
        )
        Files.writeString(projectDir.resolve("qutivex.toml"), manifest.toToml())
        envManager.ensureEnvironment(projectDir, manifest)

        val dummyFile = projectDir.resolve(".qutivex/cache/artifact.jar")
        Files.writeString(dummyFile, "cached")
        assertTrue(Files.exists(dummyFile))

        val stdout = StringWriter()
        val stderr = StringWriter()
        envManager.recreate(projectDir, PrintWriter(stdout), PrintWriter(stderr))

        assertFalse(Files.exists(dummyFile))
        assertTrue(Files.exists(projectDir.resolve(".qutivex/env/env.toml")))
        assertTrue(stdout.toString().contains("Recreated project environment"))
    }

    @Test
    fun `info fails when no qutivex toml manifest found`() {
        val emptyDir = tempDir.resolve("non-project")
        Files.createDirectories(emptyDir)

        assertFailsWith<EnvironmentException> {
            envManager.info(emptyDir)
        }
    }

    @Test
    fun `recreate restores classpath immediately and info reflects it`() {
        val projectDir = tempDir.resolve("recreate-cp-app")
        Files.createDirectories(projectDir)
        val manifest = ManifestSpec(
            project = ManifestProject(name = "recreate-cp-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
            dependencies = mapOf("org.jetbrains:annotations" to "23.0.0"),
        )
        Files.writeString(projectDir.resolve("qutivex.toml"), manifest.toToml())

        val stdout = StringWriter()
        val stderr = StringWriter()
        envManager.recreate(projectDir, PrintWriter(stdout), PrintWriter(stderr))

        val info = envManager.info(projectDir)
        assertEquals("READY", info.status)
        assertTrue(info.classpathEntriesCount > 0, "Classpath entries count must not be 0 after recreate")
        assertTrue(Files.exists(projectDir.resolve(".qutivex/env/classpath.txt")))
    }
}
