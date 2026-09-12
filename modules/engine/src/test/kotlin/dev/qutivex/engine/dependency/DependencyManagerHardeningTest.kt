package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.DependencyCoordinate
import dev.qutivex.core.dependency.DependencyGraph
import dev.qutivex.core.dependency.ResolvedDependency
import dev.qutivex.core.manifest.ManifestApplication
import dev.qutivex.core.manifest.ManifestProject
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.manifest.ManifestToolchain
import dev.qutivex.engine.backend.gradle.BackendProcessRunner
import dev.qutivex.engine.backend.gradle.GradleBackendGenerator
import dev.qutivex.engine.lockfile.LockfileException
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

class DependencyManagerHardeningTest {

    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var cacheDir: Path

    private val manifestWriter = ManifestWriter()
    private val lockfileManager = LockfileManager()
    private val backendGenerator = GradleBackendGenerator()
    private lateinit var mockRunner: MockProcessRunner
    private lateinit var artifactCache: LocalArtifactCache
    private lateinit var dependencyManager: DependencyManager

    private val initialManifest = ManifestSpec(
        project = ManifestProject("hardened-app", "0.1.0"),
        application = ManifestApplication("MainKt"),
        toolchain = ManifestToolchain("2.4.10", 21),
        dependencies = mapOf("org.example:sample-lib" to "1.0.0"),
        testDependencies = emptyMap(),
    )

    @BeforeEach
    fun setUp() {
        mockRunner = MockProcessRunner()
        artifactCache = LocalArtifactCache(customDir = cacheDir)
        val resolver = GradleDependencyResolver(backendGenerator, mockRunner)

        dependencyManager = DependencyManager(
            manifestParser = ManifestParser(),
            manifestWriter = manifestWriter,
            lockfileManager = lockfileManager,
            backendGenerator = backendGenerator,
            processRunner = mockRunner,
            dependencyResolver = resolver,
            artifactCache = artifactCache,
        )

        // Seed manifest
        manifestWriter.write(tempDir.resolve("qutivex.toml"), initialManifest)
    }

    @Test
    fun `offline and frozen install succeeds when cached artifact and lockfile are valid`() {
        // Create fake cached jar and compute checksum
        val jarFile = cacheDir.resolve("sample-lib-1.0.0.jar")
        Files.writeString(jarFile, "sample-library-binary-bytes")
        val sha256 = LocalArtifactCache.computeSha256(jarFile)

        // Write matching lockfile with the computed checksum
        val graph = DependencyGraph(
            listOf(
                ResolvedDependency(
                    group = "org.example",
                    artifact = "sample-lib",
                    version = "1.0.0",
                    scope = "runtime",
                    direct = true,
                    checksum = "sha256:$sha256",
                    repository = "https://repo.maven.apache.org/maven2/",
                )
            )
        )
        lockfileManager.write(tempDir, initialManifest, graph)
        val lockfileContentBefore = Files.readString(tempDir.resolve("qutivex.lock"))

        val out = StringWriter()
        val err = StringWriter()
        val exitCode = dependencyManager.install(
            projectDir = tempDir,
            frozen = true,
            offline = true,
            verbose = false,
            stdout = PrintWriter(out),
            stderr = PrintWriter(err),
        )

        assertEquals(0, exitCode)
        assertTrue(mockRunner.lastTasks.isEmpty(), "Install must not execute Gradle tasks")

        // Verify lockfile was NOT modified during frozen install
        val lockfileContentAfter = Files.readString(tempDir.resolve("qutivex.lock"))
        assertEquals(lockfileContentBefore, lockfileContentAfter)
    }

    @Test
    fun `offline and frozen install rejects manifest mismatch`() {
        val jarFile = cacheDir.resolve("sample-lib-1.0.0.jar")
        Files.writeString(jarFile, "sample-library-binary-bytes")
        val sha256 = LocalArtifactCache.computeSha256(jarFile)

        val graph = DependencyGraph(
            listOf(
                ResolvedDependency("org.example", "sample-lib", "1.0.0", "runtime", direct = true, checksum = "sha256:$sha256")
            )
        )
        lockfileManager.write(tempDir, initialManifest, graph)

        // Tamper manifest version
        val tamperedManifest = initialManifest.withDependency("org.example:sample-lib", "1.1.0")
        manifestWriter.write(tempDir.resolve("qutivex.toml"), tamperedManifest)

        val ex = assertThrows<LockfileException> {
            dependencyManager.install(
                projectDir = tempDir,
                frozen = true,
                offline = true,
                verbose = false,
                stdout = PrintWriter(StringWriter()),
                stderr = PrintWriter(StringWriter()),
            )
        }
        assertTrue(ex.message!!.contains("Lockfile is out of sync with qutivex.toml in frozen mode"))
    }

    @Test
    fun `offline and frozen install fails with precise error when artifact is missing`() {
        val graph = DependencyGraph(
            listOf(
                ResolvedDependency("org.example", "sample-lib", "1.0.0", "runtime", direct = true, checksum = "sha256:abcdef123456")
            )
        )
        lockfileManager.write(tempDir, initialManifest, graph)
        // Note: jar file is NOT created in cacheDir

        val ex = assertThrows<MissingOfflineArtifactException> {
            dependencyManager.install(
                projectDir = tempDir,
                frozen = true,
                offline = true,
                verbose = false,
                stdout = PrintWriter(StringWriter()),
                stderr = PrintWriter(StringWriter()),
            )
        }
        assertEquals("org.example", ex.group)
        assertEquals("sample-lib", ex.artifact)
        assertEquals("1.0.0", ex.version)
        assertTrue(ex.message!!.contains("Offline installation cannot continue."))
        assertTrue(ex.message!!.contains("Missing artifact:\norg.example:sample-lib:1.0.0"))
    }

    @Test
    fun `frozen install rejects tampered cached artifact`() {
        val jarFile = cacheDir.resolve("sample-lib-1.0.0.jar")
        Files.writeString(jarFile, "original-valid-bytes")
        val originalSha = LocalArtifactCache.computeSha256(jarFile)

        val graph = DependencyGraph(
            listOf(
                ResolvedDependency("org.example", "sample-lib", "1.0.0", "runtime", direct = true, checksum = "sha256:$originalSha")
            )
        )
        lockfileManager.write(tempDir, initialManifest, graph)

        // Tamper with cached JAR file
        Files.writeString(jarFile, "tampered-malicious-bytes")

        val ex = assertThrows<ArtifactIntegrityException> {
            dependencyManager.install(
                projectDir = tempDir,
                frozen = true,
                offline = true,
                verbose = false,
                stdout = PrintWriter(StringWriter()),
                stderr = PrintWriter(StringWriter()),
            )
        }
        assertEquals("org.example", ex.group)
        assertEquals("sample-lib", ex.artifact)
        assertEquals("1.0.0", ex.version)
        assertEquals(originalSha, ex.expectedSha256)
        assertTrue(ex.message!!.contains("Artifact integrity verification failed."))
    }

    @Test
    fun `frozen install rejects toolchain and backend mismatch`() {
        val jarFile = cacheDir.resolve("sample-lib-1.0.0.jar")
        Files.writeString(jarFile, "bytes")
        val sha256 = LocalArtifactCache.computeSha256(jarFile)

        val graph = DependencyGraph(
            listOf(
                ResolvedDependency("org.example", "sample-lib", "1.0.0", "runtime", direct = true, checksum = "sha256:$sha256")
            )
        )
        lockfileManager.write(tempDir, initialManifest, graph)

        // Modify toolchain in manifest
        val modifiedToolchainManifest = initialManifest.copy(toolchain = ManifestToolchain("2.3.0", 17))
        manifestWriter.write(tempDir.resolve("qutivex.toml"), modifiedToolchainManifest)

        val ex = assertThrows<LockfileException> {
            dependencyManager.install(
                projectDir = tempDir,
                frozen = true,
                offline = false,
                verbose = false,
                stdout = PrintWriter(StringWriter()),
                stderr = PrintWriter(StringWriter()),
            )
        }
        assertTrue(ex.message!!.contains("in frozen mode"))
    }

    private class MockProcessRunner : BackendProcessRunner {
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
            return 0
        }
    }
}
