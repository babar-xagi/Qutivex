package dev.qutivex.engine.lockfile

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
        val written = manager.write(tempDir, manifest)

        val lockfilePath = tempDir.resolve("qutivex.lock")
        assertTrue(Files.isRegularFile(lockfilePath))

        val read = manager.read(tempDir)
        assertNotNull(read)
        assertEquals(written.version, read.version)
        assertEquals(written.manifestHash, read.manifestHash)
        assertEquals(written.kotlinVersion, read.kotlinVersion)
        assertEquals(written.jvmTarget, read.jvmTarget)
        assertEquals(written.dependencies, read.dependencies)
        assertEquals(written.testDependencies, read.testDependencies)
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
