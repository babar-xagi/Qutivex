package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.DependencyCoordinate
import dev.qutivex.core.manifest.ManifestApplication
import dev.qutivex.core.manifest.ManifestProject
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.manifest.ManifestToolchain
import dev.qutivex.engine.backend.gradle.BackendProcessRunner
import dev.qutivex.engine.backend.gradle.GradleBackendGenerator
import dev.qutivex.engine.lockfile.LockfileManager
import dev.qutivex.engine.manifest.ManifestParser
import dev.qutivex.engine.manifest.ManifestWriter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val manifestWriter = ManifestWriter()
    private val lockfileManager = LockfileManager()
    private val backendGenerator = GradleBackendGenerator()
    private lateinit var mockRunner: MockProcessRunner
    private lateinit var dependencyManager: DependencyManager

    @BeforeEach
    fun setUp() {
        mockRunner = MockProcessRunner()
        val resolver = GradleDependencyResolver(
            backendGenerator = backendGenerator,
            processRunner = mockRunner,
        )
        dependencyManager = DependencyManager(
            manifestParser = ManifestParser(),
            manifestWriter = manifestWriter,
            lockfileManager = lockfileManager,
            backendGenerator = backendGenerator,
            processRunner = mockRunner,
            dependencyResolver = resolver,
        )

        // Seed initial project manifest
        val initialManifest = ManifestSpec(
            project = ManifestProject("test-project", "0.1.0"),
            application = ManifestApplication("test.MainKt"),
            toolchain = ManifestToolchain("2.4.10", 21),
            dependencies = emptyMap(),
            testDependencies = emptyMap(),
        )
        manifestWriter.write(tempDir.resolve("qutivex.toml"), initialManifest)
    }

    @Test
    fun `add adds runtime dependency and writes lockfile when resolution succeeds`() {
        mockRunner.exitCodeToReturn = 0
        val out = StringWriter()
        val err = StringWriter()

        val coord = DependencyCoordinate.parse("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
        val manifest = dependencyManager.add(tempDir, coord, false, false, PrintWriter(out), PrintWriter(err))

        assertEquals("1.8.0", manifest.dependencies["org.jetbrains.kotlinx:kotlinx-coroutines-core"])
        assertEquals(listOf("qutivexResolve"), mockRunner.lastTasks)
        assertTrue(mockRunner.lastExtraArgs.contains("--build-cache"))

        // Check lockfile
        val lock = lockfileManager.read(tempDir)
        assertEquals("1.8.0", lock?.dependencies?.get("org.jetbrains.kotlinx:kotlinx-coroutines-core"))
    }

    @Test
    fun `add adds test dependency when isTest is true`() {
        mockRunner.exitCodeToReturn = 0
        val out = StringWriter()
        val err = StringWriter()

        val coord = DependencyCoordinate.parse("org.junit.jupiter:junit-jupiter:5.10.2")
        val manifest = dependencyManager.add(tempDir, coord, true, false, PrintWriter(out), PrintWriter(err))

        assertEquals("5.10.2", manifest.testDependencies["org.junit.jupiter:junit-jupiter"])
        assertEquals(listOf("qutivexResolve"), mockRunner.lastTasks)

        val lock = lockfileManager.read(tempDir)
        assertEquals("5.10.2", lock?.testDependencies?.get("org.junit.jupiter:junit-jupiter"))
    }

    @Test
    fun `add rolls back manifest if resolution fails`() {
        mockRunner.exitCodeToReturn = 1
        val out = StringWriter()
        val err = StringWriter()

        val coord = DependencyCoordinate.parse("nonexistent:pkg:99.99")
        val ex = assertThrows<DependencyException> {
            dependencyManager.add(tempDir, coord, false, false, PrintWriter(out), PrintWriter(err))
        }

        assertTrue(ex.message?.contains("Failed to resolve") == true)

        // Manifest must NOT have nonexistent dependency after rollback
        val currentManifest = dependencyManager.list(tempDir)
        assertFalse(currentManifest.dependencies.containsKey("nonexistent:pkg"))
    }

    @Test
    fun `remove removes existing runtime dependency`() {
        // First add dependency
        val manifest = ManifestParser().parse(tempDir.resolve("qutivex.toml"))
            .withDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core", "1.8.0")
        manifestWriter.write(tempDir.resolve("qutivex.toml"), manifest)

        val updated = dependencyManager.remove(tempDir, "org.jetbrains.kotlinx:kotlinx-coroutines-core", false)
        assertFalse(updated.dependencies.containsKey("org.jetbrains.kotlinx:kotlinx-coroutines-core"))

        val lock = lockfileManager.read(tempDir)
        assertFalse(lock?.dependencies?.containsKey("org.jetbrains.kotlinx:kotlinx-coroutines-core") == true)
    }

    @Test
    fun `remove throws DependencyException when dependency does not exist`() {
        val ex = assertThrows<DependencyException> {
            dependencyManager.remove(tempDir, "nonexistent:dep", false)
        }
        assertTrue(ex.message?.contains("was not found") == true)
    }

    @Test
    fun `list returns current project manifest`() {
        val manifest = dependencyManager.list(tempDir)
        assertEquals("test-project", manifest.project.name)
    }

    @Test
    fun `install executes build and updates lockfile`() {
        mockRunner.exitCodeToReturn = 0
        val out = StringWriter()
        val err = StringWriter()

        val exitCode = dependencyManager.install(
            tempDir,
            frozen = false,
            offline = true,
            verbose = false,
            stdout = PrintWriter(out),
            stderr = PrintWriter(err),
        )
        assertEquals(0, exitCode)
        assertEquals(listOf("classes", "testClasses"), mockRunner.lastTasks)
        assertTrue(mockRunner.lastExtraArgs.contains("--offline"))

        val lock = lockfileManager.read(tempDir)
        assertTrue(lock != null)
    }

    private class MockProcessRunner : BackendProcessRunner {
        var exitCodeToReturn: Int = 0
        var lastTasks: List<String> = emptyList()
        var lastExtraArgs: List<String> = emptyList()

        override fun execute(
            projectDir: Path,
            tasks: List<String>,
            extraArgs: List<String>,
            stdout: PrintWriter,
            stderr: PrintWriter,
            stdin: InputStream?,
        ): Int {
            lastTasks = tasks
            lastExtraArgs = extraArgs
            if (tasks.contains("qutivexResolve") && exitCodeToReturn == 0) {
                val gradleDir = projectDir.resolve(".qutivex/gradle")
                Files.createDirectories(gradleDir)
                val file = gradleDir.resolve("resolved-dependencies.txt")
                val manifestFile = projectDir.resolve("qutivex.toml")
                val content = if (Files.exists(manifestFile)) {
                    val m = ManifestParser().parse(manifestFile)
                    buildString {
                        m.dependencies.forEach { (coord, ver) ->
                            val parts = coord.split(":")
                            val group = parts.getOrNull(0) ?: coord
                            val artifact = parts.getOrNull(1) ?: coord
                            append("$group\t$artifact\t$ver\truntime\ttrue\tsha256:abc\thttps://repo.maven.apache.org/maven2/\t\n")
                        }
                        m.testDependencies.forEach { (coord, ver) ->
                            val parts = coord.split(":")
                            val group = parts.getOrNull(0) ?: coord
                            val artifact = parts.getOrNull(1) ?: coord
                            append("$group\t$artifact\t$ver\ttest\ttrue\tsha256:def\thttps://repo.maven.apache.org/maven2/\t\n")
                        }
                    }
                } else ""
                Files.writeString(file, content)
            }
            return exitCodeToReturn
        }
    }
}
