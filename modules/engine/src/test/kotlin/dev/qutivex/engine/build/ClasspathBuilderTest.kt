package dev.qutivex.engine.build

import dev.qutivex.core.dependency.ResolvedDependency
import dev.qutivex.core.lockfile.LockfileSpec
import dev.qutivex.core.manifest.ManifestApplication
import dev.qutivex.core.manifest.ManifestProject
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.manifest.ManifestToolchain
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClasspathBuilderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `detects JUnit 5_12_2 alignment from lockfile packages`() {
        val lockfile = LockfileSpec(
            manifestHash = "test-hash",
            packages = listOf(
                ResolvedDependency(
                    group = "org.junit.jupiter",
                    artifact = "junit-jupiter-engine",
                    version = "5.12.2",
                    scope = "test",
                    direct = true,
                ),
                ResolvedDependency(
                    group = "org.junit.platform",
                    artifact = "junit-platform-engine",
                    version = "1.12.2",
                    scope = "test",
                    direct = false,
                ),
            ),
        )

        val alignment = ClasspathBuilder.detectJUnitVersionAlignment(manifest = null, lockfile = lockfile)
        assertEquals("1.12.2", alignment.platformVersion)
        assertEquals("5.12.2", alignment.jupiterVersion)
    }

    @Test
    fun `detects JUnit 5_10_1 alignment from manifest test dependencies`() {
        val manifest = ManifestSpec(
            schemaVersion = 1,
            project = ManifestProject("test-app", "0.1.0"),
            toolchain = ManifestToolchain("2.4.10", 21),
            application = ManifestApplication("MainKt"),
            dependencies = emptyMap(),
            testDependencies = mapOf("org.junit.jupiter:junit-jupiter" to "5.10.1"),
        )

        val alignment = ClasspathBuilder.detectJUnitVersionAlignment(manifest = manifest, lockfile = null)
        assertEquals("1.10.1", alignment.platformVersion)
        assertEquals("5.10.1", alignment.jupiterVersion)
    }

    @Test
    fun `detects default aligned versions when no junit is declared`() {
        val alignment = ClasspathBuilder.detectJUnitVersionAlignment(manifest = null, lockfile = null)
        assertEquals(ClasspathBuilder.DEFAULT_PLATFORM_VERSION, alignment.platformVersion)
        assertEquals(ClasspathBuilder.DEFAULT_JUPITER_VERSION, alignment.jupiterVersion)
    }

    @Test
    fun `parses standard maven jar names correctly`() {
        val jar1 = tempDir.resolve("junit-platform-launcher-1.12.2.jar")
        val info1 = ClasspathBuilder.parseJarInfo(jar1)
        assertEquals("junit-platform-launcher", info1?.artifactName)
        assertEquals("1.12.2", info1?.version)

        val jar2 = tempDir.resolve("junit-jupiter-engine-5.12.2.jar")
        val info2 = ClasspathBuilder.parseJarInfo(jar2)
        assertEquals("junit-jupiter-engine", info2?.artifactName)
        assertEquals("5.12.2", info2?.version)

        val jar3 = tempDir.resolve("opentest4j-1.3.0.jar")
        val info3 = ClasspathBuilder.parseJarInfo(jar3)
        assertEquals("opentest4j", info3?.artifactName)
        assertEquals("1.3.0", info3?.version)
    }

    @Test
    fun `purges conflicting JUnit Platform versions and keeps aligned version`() {
        // Create mock jars on disk
        val alignedLauncher = tempDir.resolve("junit-platform-launcher-1.12.2.jar").also { Files.createFile(it) }
        val conflictingLauncher = tempDir.resolve("junit-platform-launcher-1.10.1.jar").also { Files.createFile(it) }
        val alignedEngine = tempDir.resolve("junit-platform-engine-1.12.2.jar").also { Files.createFile(it) }
        val conflictingEngine = tempDir.resolve("junit-platform-engine-1.10.1.jar").also { Files.createFile(it) }
        val alignedJupiter = tempDir.resolve("junit-jupiter-engine-5.12.2.jar").also { Files.createFile(it) }
        val conflictingJupiter = tempDir.resolve("junit-jupiter-engine-5.10.1.jar").also { Files.createFile(it) }
        val regularDep = tempDir.resolve("kotlinx-coroutines-core-1.10.2.jar").also { Files.createFile(it) }

        val rawClasspath = listOf(
            conflictingLauncher,
            alignedLauncher,
            conflictingEngine,
            alignedEngine,
            conflictingJupiter,
            alignedJupiter,
            regularDep,
        )

        val alignment = JUnitVersionAlignment(platformVersion = "1.12.2", jupiterVersion = "5.12.2")
        val filtered = ClasspathBuilder.alignAndDeduplicateClasspath(rawClasspath, alignment)

        // Conflicting 1.10.1 jars must be purged
        assertFalse(filtered.contains(conflictingLauncher), "Conflicting launcher 1.10.1 must be purged")
        assertFalse(filtered.contains(conflictingEngine), "Conflicting engine 1.10.1 must be purged")
        assertFalse(filtered.contains(conflictingJupiter), "Conflicting jupiter 5.10.1 must be purged")

        // Aligned 1.12.2 / 5.12.2 jars must be retained
        assertTrue(filtered.contains(alignedLauncher), "Aligned launcher 1.12.2 must be kept")
        assertTrue(filtered.contains(alignedEngine), "Aligned engine 1.12.2 must be kept")
        assertTrue(filtered.contains(alignedJupiter), "Aligned jupiter 5.12.2 must be kept")
        assertTrue(filtered.contains(regularDep), "Regular dependency must be kept")
    }

    @Test
    fun `deduplicates identical artifact jars on test classpath`() {
        val dir1 = tempDir.resolve("cache1").also { Files.createDirectories(it) }
        val dir2 = tempDir.resolve("cache2").also { Files.createDirectories(it) }

        val jarA1 = dir1.resolve("junit-platform-commons-1.12.2.jar").also { Files.createFile(it) }
        val jarA2 = dir2.resolve("junit-platform-commons-1.12.2.jar").also { Files.createFile(it) }

        val alignment = JUnitVersionAlignment(platformVersion = "1.12.2", jupiterVersion = "5.12.2")
        val filtered = ClasspathBuilder.alignAndDeduplicateClasspath(listOf(jarA1, jarA2), alignment)

        assertEquals(1, filtered.size, "Duplicate junit-platform-commons jars must be deduplicated to 1")
    }
}
