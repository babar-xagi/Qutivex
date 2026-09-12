package dev.qutivex.engine.lockfile

import dev.qutivex.core.dependency.DependencyGraph
import dev.qutivex.core.dependency.ResolvedDependency
import dev.qutivex.core.manifest.ManifestApplication
import dev.qutivex.core.manifest.ManifestProject
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.manifest.ManifestToolchain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LockfileManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val manager = LockfileManager()

    private fun sampleManifest(): ManifestSpec = ManifestSpec(
        project = ManifestProject("sample-app", "0.1.0"),
        application = ManifestApplication("sample.app.MainKt"),
        toolchain = ManifestToolchain("2.4.10", 21),
        dependencies = mapOf("org.jetbrains.kotlinx:kotlinx-coroutines-core" to "1.8.0"),
        testDependencies = mapOf("org.junit.jupiter:junit-jupiter" to "5.10.2"),
    )

    @Test
    fun `read returns null when lockfile does not exist`() {
        val result = manager.read(tempDir)
        assertNull(result)
    }

    @Test
    fun `write creates lockfile and read retrieves matching spec`() {
        val manifest = sampleManifest()
        val graph = DependencyGraph(
            rootProjectName = "sample-app",
            packages = listOf(
                ResolvedDependency(
                    group = "org.jetbrains.kotlinx",
                    artifact = "kotlinx-coroutines-core",
                    version = "1.8.0",
                    scope = "runtime",
                    direct = true,
                    dependencies = listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"),
                    checksum = "sha256:abc123",
                ),
                ResolvedDependency(
                    group = "org.jetbrains.kotlinx",
                    artifact = "kotlinx-coroutines-core-jvm",
                    version = "1.8.0",
                    scope = "runtime",
                    direct = false,
                    dependencies = listOf("org.jetbrains.kotlin:kotlin-stdlib"),
                    checksum = "sha256:def456",
                ),
                ResolvedDependency(
                    group = "org.junit.jupiter",
                    artifact = "junit-jupiter",
                    version = "5.10.2",
                    scope = "test",
                    direct = true,
                ),
            ),
        )

        val written = manager.write(tempDir, manifest, graph)

        val lockfilePath = tempDir.resolve("qutivex.lock")
        assertTrue(Files.isRegularFile(lockfilePath))
        val lockContent = Files.readString(lockfilePath)
        assertTrue(lockContent.contains("[[package]]"))
        assertTrue(lockContent.contains("kotlinx-coroutines-core-jvm"))

        val read = manager.read(tempDir)
        assertNotNull(read)
        assertEquals(written.version, read.version)
        assertEquals(written.manifestHash, read.manifestHash)
        assertEquals(written.kotlinVersion, read.kotlinVersion)
        assertEquals(written.jvmTarget, read.jvmTarget)
        assertEquals(3, read.packages.size)

        val coroutines = read.packages.first { it.artifact == "kotlinx-coroutines-core" }
        assertTrue(coroutines.direct)
        assertEquals("sha256:abc123", coroutines.checksum)

        val jvm = read.packages.first { it.artifact == "kotlinx-coroutines-core-jvm" }
        assertFalse(jvm.direct)
    }

    @Test
    fun `verifyFrozen succeeds when lockfile matches manifest`() {
        val manifest = sampleManifest()
        manager.write(tempDir, manifest)

        assertDoesNotThrow {
            manager.verifyFrozen(tempDir, manifest)
        }
    }

    @Test
    fun `verifyFrozen fails when lockfile is missing`() {
        val manifest = sampleManifest()
        val ex = assertThrows<LockfileException> {
            manager.verifyFrozen(tempDir, manifest)
        }
        assertTrue(ex.message?.contains("Frozen lockfile mode requires 'qutivex.lock'") == true)
    }

    @Test
    fun `verifyFrozen fails when manifest hash differs`() {
        val manifest = sampleManifest()
        manager.write(tempDir, manifest)

        val modifiedManifest = manifest.withDependency("com.google.code.gson:gson", "2.10.1")
        val ex = assertThrows<LockfileException> {
            manager.verifyFrozen(tempDir, modifiedManifest)
        }
        assertTrue(ex.message?.contains("Lockfile is out of sync") == true)
    }
}
