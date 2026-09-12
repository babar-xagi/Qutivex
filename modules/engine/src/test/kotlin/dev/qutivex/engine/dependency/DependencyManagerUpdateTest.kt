package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.DependencyCoordinate
import dev.qutivex.core.dependency.DependencyGraph
import dev.qutivex.core.dependency.ResolvedDependency
import dev.qutivex.core.manifest.ManifestApplication
import dev.qutivex.core.manifest.ManifestProject
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.manifest.ManifestToolchain
import dev.qutivex.engine.backend.gradle.GradleBackendGenerator
import dev.qutivex.engine.lockfile.LockfileManager
import dev.qutivex.engine.manifest.ManifestParser
import dev.qutivex.engine.manifest.ManifestWriter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyManagerUpdateTest {

    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var cacheDir: Path

    private val manifestWriter = ManifestWriter()
    private val manifestParser = ManifestParser()
    private val lockfileManager = LockfileManager()
    private val backendGenerator = GradleBackendGenerator()
    private lateinit var artifactCache: LocalArtifactCache
    private lateinit var stubResolver: StubResolver
    private lateinit var dependencyManager: DependencyManager

    private val initialManifest = ManifestSpec(
        project = ManifestProject("update-app", "0.1.0"),
        application = ManifestApplication("MainKt"),
        toolchain = ManifestToolchain("2.4.10", 21),
        dependencies = mapOf("org.example:sample-lib" to "1.0.0"),
        testDependencies = mapOf("org.example:test-lib" to "1.0.0"),
    )

    class StubResolver : DependencyResolver {
        var shouldFail = false
        var nextGraph: DependencyGraph? = null

        override fun resolve(
            projectDir: Path,
            manifest: ManifestSpec,
            offline: Boolean,
            verbose: Boolean,
        ): DependencyGraph {
            if (shouldFail) {
                throw RuntimeException("Could not find org.example:sample-lib:9.9.9")
            }
            return nextGraph ?: DependencyGraph(
                manifest.dependencies.map { (k, v) ->
                    val (g, a) = k.split(":")
                    ResolvedDependency(g, a, v, "runtime", direct = true)
                } + manifest.testDependencies.map { (k, v) ->
                    val (g, a) = k.split(":")
                    ResolvedDependency(g, a, v, "test", direct = true)
                }
            )
        }
    }

    @BeforeEach
    fun setUp() {
        stubResolver = StubResolver()
        artifactCache = LocalArtifactCache(customDir = cacheDir)

        dependencyManager = DependencyManager(
            manifestParser = manifestParser,
            manifestWriter = manifestWriter,
            lockfileManager = lockfileManager,
            backendGenerator = backendGenerator,
            dependencyResolver = stubResolver,
            artifactCache = artifactCache,
        )

        // Seed initial manifest and lockfile
        manifestWriter.write(tempDir.resolve("qutivex.toml"), initialManifest)
        val initialGraph = DependencyGraph(
            listOf(
                ResolvedDependency("org.example", "sample-lib", "1.0.0", "runtime", direct = true, dependencies = listOf("org.example:transitive-dep")),
                ResolvedDependency("org.example", "transitive-dep", "1.0.0", "runtime", direct = false),
                ResolvedDependency("org.example", "test-lib", "1.0.0", "test", direct = true),
            )
        )
        lockfileManager.write(tempDir, initialManifest, initialGraph)
    }

    @Test
    fun `update modifies manifest, updates lockfile, and reports transitive changes`() {
        // Setup new resolved graph with upgraded transitive dependency
        stubResolver.nextGraph = DependencyGraph(
            listOf(
                ResolvedDependency("org.example", "sample-lib", "1.1.0", "runtime", direct = true, dependencies = listOf("org.example:transitive-dep")),
                ResolvedDependency("org.example", "transitive-dep", "1.1.0", "runtime", direct = false),
                ResolvedDependency("org.example", "test-lib", "1.0.0", "test", direct = true),
            )
        )

        val result = dependencyManager.update(
            projectDir = tempDir,
            coordinate = DependencyCoordinate.parse("org.example:sample-lib:1.1.0"),
        )

        assertEquals("1.0.0", result.oldVersion)
        assertEquals("1.1.0", result.newVersion)
        assertFalse(result.isTest)
        assertEquals(1, result.transitiveChanges.size)

        val change = result.transitiveChanges.first()
        assertEquals("org.example:transitive-dep", change.key)
        assertEquals("1.0.0", change.oldVersion)
        assertEquals("1.1.0", change.newVersion)
        assertEquals(TransitiveChange.ChangeType.UPGRADED, change.type)

        // Verify disk manifest
        val updated = manifestParser.parse(tempDir.resolve("qutivex.toml"))
        assertEquals("1.1.0", updated.dependencies["org.example:sample-lib"])

        // Verify disk lockfile
        val lock = lockfileManager.read(tempDir)!!
        val lockedSample = lock.packages.first { it.key == "org.example:sample-lib" }
        assertEquals("1.1.0", lockedSample.version)
    }

    @Test
    fun `update returns no changes if version is unchanged`() {
        val result = dependencyManager.update(
            projectDir = tempDir,
            coordinate = DependencyCoordinate.parse("org.example:sample-lib:1.0.0"),
        )

        assertEquals("1.0.0", result.oldVersion)
        assertEquals("1.0.0", result.newVersion)
        assertTrue(result.transitiveChanges.isEmpty())
    }

    @Test
    fun `update throws exception for undeclared dependency`() {
        val ex = assertThrows<DependencyException> {
            dependencyManager.update(
                projectDir = tempDir,
                coordinate = DependencyCoordinate.parse("org.unknown:lib:2.0.0"),
            )
        }
        assertContains(ex.message!!, "is not declared in qutivex.toml")
    }

    @Test
    fun `update rolls back manifest on resolution failure`() {
        stubResolver.shouldFail = true

        val ex = assertThrows<DependencyException> {
            dependencyManager.update(
                projectDir = tempDir,
                coordinate = DependencyCoordinate.parse("org.example:sample-lib:9.9.9"),
            )
        }
        assertContains(ex.message!!, "Rolled back changes")

        // Manifest must be preserved
        val manifest = manifestParser.parse(tempDir.resolve("qutivex.toml"))
        assertEquals("1.0.0", manifest.dependencies["org.example:sample-lib"])
    }

    @Test
    fun `tree returns formatted ASCII tree from lockfile`() {
        val treeOutput = dependencyManager.tree(tempDir)
        assertContains(treeOutput, "update-app v0.1.0")
        assertContains(treeOutput, "[dependencies]")
        assertContains(treeOutput, "org.example:sample-lib:1.0.0")
        assertContains(treeOutput, "org.example:transitive-dep:1.0.0")
        assertContains(treeOutput, "[test-dependencies]")
        assertContains(treeOutput, "org.example:test-lib:1.0.0")
    }
}
