package dev.qutivex.cli

import dev.qutivex.engine.dependency.LocalArtifactCache
import dev.qutivex.engine.project.ProjectLockManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase2HardeningIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val cli = QutivexCli()

    @Test
    fun `end-to-end offline and frozen combination, tamper rejection, and concurrency locking`() {
        val projectDir = tempDir.resolve("hardening-app")

        // 1. Initialize project
        val initResult = execute(listOf("init", projectDir.toString()))
        assertEquals(0, initResult.exitCode, initResult.stderr)

        // 2. Add real dependency and install to populate cache and lockfile
        val addResult = execute(listOf("add", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), projectDir)
        assertEquals(0, addResult.exitCode, addResult.stderr)

        val installResult = execute(listOf("install"), projectDir)
        assertEquals(0, installResult.exitCode, installResult.stderr)
        assertTrue(Files.exists(projectDir.resolve("qutivex.lock")))

        // 3. install --frozen succeeds
        val frozenResult = execute(listOf("install", "--frozen"), projectDir)
        assertEquals(0, frozenResult.exitCode, frozenResult.stderr)

        // 4. install --offline succeeds
        val offlineResult = execute(listOf("install", "--offline"), projectDir)
        assertEquals(0, offlineResult.exitCode, offlineResult.stderr)

        // 5. install --offline --frozen succeeds
        val offlineFrozenResult = execute(listOf("install", "--offline", "--frozen"), projectDir)
        assertEquals(0, offlineFrozenResult.exitCode, offlineFrozenResult.stderr)
        assertTrue(
            offlineFrozenResult.stdout.contains("Dependencies verified from local cache"),
            offlineFrozenResult.stdout,
        )

        // 6. install --offline --frozen rejects tampered manifest
        val originalManifest = Files.readString(projectDir.resolve("qutivex.toml"))
        val tamperedManifest = originalManifest.replace("1.10.2", "1.9.0")
        Files.writeString(projectDir.resolve("qutivex.toml"), tamperedManifest)

        val mismatchResult = execute(listOf("install", "--offline", "--frozen"), projectDir)
        assertEquals(1, mismatchResult.exitCode)
        assertTrue(
            mismatchResult.stderr.contains("Lockfile is out of sync with qutivex.toml in frozen mode") ||
            mismatchResult.stderr.contains("Lockfile direct dependencies do not match"),
            mismatchResult.stderr,
        )

        // Restore original manifest
        Files.writeString(projectDir.resolve("qutivex.toml"), originalManifest)

        // 7. Toolchain mismatch in frozen mode
        val toolchainMismatched = originalManifest.replace("jvm = 21", "jvm = 17")
        Files.writeString(projectDir.resolve("qutivex.toml"), toolchainMismatched)

        val toolchainFailResult = execute(listOf("install", "--frozen"), projectDir)
        assertEquals(1, toolchainFailResult.exitCode)
        assertTrue(toolchainFailResult.stderr.contains("Toolchain mismatch in frozen mode"), toolchainFailResult.stderr)

        // Restore original manifest
        Files.writeString(projectDir.resolve("qutivex.toml"), originalManifest)

        // 8. Test tampered cached JAR rejection
        val cache = LocalArtifactCache()
        val cachedJar = cache.findArtifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")
        if (cachedJar != null && Files.exists(cachedJar)) {
            val backupBytes = Files.readAllBytes(cachedJar)
            try {
                // Modify a single byte to corrupt the artifact
                val corruptedBytes = backupBytes.copyOf()
                corruptedBytes[0] = (corruptedBytes[0] + 1).toByte()
                Files.write(cachedJar, corruptedBytes)

                val tamperResult = execute(listOf("install", "--offline", "--frozen"), projectDir)
                assertEquals(1, tamperResult.exitCode)
                assertTrue(
                    tamperResult.stderr.contains("Artifact integrity verification failed"),
                    tamperResult.stderr,
                )
                assertTrue(
                    tamperResult.stderr.contains("kotlinx-coroutines-core-jvm:1.10.2"),
                    tamperResult.stderr,
                )
            } finally {
                // Restore authentic bytes
                Files.write(cachedJar, backupBytes)
            }
        }

        // 9. Concurrency lock rejection
        val lock = ProjectLockManager.acquire(projectDir, "add", timeoutMs = 200)
        try {
            val concurrentResult = execute(listOf("install"), projectDir)
            assertEquals(1, concurrentResult.exitCode)
            assertTrue(
                concurrentResult.stderr.contains("Another Qutivex operation is currently modifying this project"),
                concurrentResult.stderr,
            )
            assertTrue(concurrentResult.stderr.contains("Operation: add"), concurrentResult.stderr)
        } finally {
            lock.close()
        }

        // 10. Post-lock operation executes successfully without leftovers
        assertFalse(Files.exists(projectDir.resolve(".qutivex/project.lock")))
        val postResult = execute(listOf("install", "--offline", "--frozen"), projectDir)
        assertEquals(0, postResult.exitCode, postResult.stderr)
    }

    private fun execute(args: List<String>, workingDirectory: Path = tempDir): Result {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val exitCode = cli.execute(args, workingDirectory, java.io.PrintWriter(stdout), java.io.PrintWriter(stderr))
        return Result(exitCode, stdout.toString(), stderr.toString())
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)
}
