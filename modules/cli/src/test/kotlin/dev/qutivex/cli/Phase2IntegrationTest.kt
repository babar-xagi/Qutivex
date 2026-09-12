package dev.qutivex.cli

import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase2IntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val cli = QutivexCli()

    @Test
    fun `end-to-end dependency lifecycle add, run, list, install frozen, remove`() {
        val projectDir = tempDir.resolve("phase2-app")

        // 1. Initialize project
        val initResult = execute(listOf("init", projectDir.toString()))
        assertEquals(0, initResult.exitCode, initResult.stderr)
        assertTrue(initResult.stdout.contains("Initialized"))
        assertTrue(initResult.stdout.contains("✨"))

        // 2. Add dependency: kotlinx-coroutines
        val addResult = execute(listOf("add", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), projectDir)
        assertEquals(0, addResult.exitCode, addResult.stderr)
        assertTrue(addResult.stdout.contains("Added org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), addResult.stdout)
        assertTrue(addResult.stdout.contains("➕"))

        val manifestContent = Files.readString(projectDir.resolve("qutivex.toml"))
        assertTrue(manifestContent.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core"))

        val lockFile = projectDir.resolve("qutivex.lock")
        assertTrue(Files.exists(lockFile))
        val lockContent = Files.readString(lockFile)
        assertTrue(lockContent.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core"))

        // 3. Update Main.kt to use the added dependency
        val mainFile = projectDir.resolve("src/main/kotlin/Main.kt")
        Files.writeString(
            mainFile,
            """
                import kotlinx.coroutines.runBlocking

                fun main() = runBlocking {
                    println("Phase 2 Coroutines Active!")
                }
            """.trimIndent(),
        )

        // 4. Run application
        val runResult = execute(listOf("run"), projectDir)
        assertEquals(0, runResult.exitCode, runResult.stderr)
        assertTrue(runResult.stdout.contains("Phase 2 Coroutines Active!"), runResult.stdout)
        assertTrue(runResult.stdout.contains("✨ Finished in"), runResult.stdout)

        // 5. List dependencies
        val listResult = execute(listOf("list"), projectDir)
        assertEquals(0, listResult.exitCode, listResult.stderr)
        assertTrue(listResult.stdout.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), listResult.stdout)
        assertTrue(listResult.stdout.contains("📋 Dependencies"), listResult.stdout)

        // 6. Install --frozen should succeed because lockfile matches toml
        val installFrozenResult = execute(listOf("install", "--frozen"), projectDir)
        assertEquals(0, installFrozenResult.exitCode, installFrozenResult.stderr)
        assertTrue(installFrozenResult.stdout.contains("Dependencies locked and installed"), installFrozenResult.stdout)

        // 7. Remove dependency
        // Restore Main.kt first so it doesn't need coroutines anymore
        Files.writeString(
            mainFile,
            """
                fun main() {
                    println("Back to standard Kotlin!")
                }
            """.trimIndent(),
        )

        val removeResult = execute(listOf("remove", "org.jetbrains.kotlinx:kotlinx-coroutines-core"), projectDir)
        assertEquals(0, removeResult.exitCode, removeResult.stderr)
        assertTrue(removeResult.stdout.contains("Removed org.jetbrains.kotlinx:kotlinx-coroutines-core"), removeResult.stdout)
        assertTrue(removeResult.stdout.contains("➖"), removeResult.stdout)

        val updatedManifest = Files.readString(projectDir.resolve("qutivex.toml"))
        assertFalse(updatedManifest.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core"))

        val updatedLock = Files.readString(lockFile)
        assertFalse(updatedLock.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core"))

        // 8. Run again to ensure removal took effect cleanly
        val runAfterRemoveResult = execute(listOf("run"), projectDir)
        assertEquals(0, runAfterRemoveResult.exitCode, runAfterRemoveResult.stderr)
        assertTrue(runAfterRemoveResult.stdout.contains("Back to standard Kotlin!"), runAfterRemoveResult.stdout)
    }

    private fun execute(args: List<String>, workingDirectory: Path = tempDir): Result {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val exitCode = cli.execute(args, workingDirectory, PrintWriter(stdout), PrintWriter(stderr))
        return Result(exitCode, stdout.toString(), stderr.toString())
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)
}
