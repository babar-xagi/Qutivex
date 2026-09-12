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
    fun `end-to-end dependency lifecycle, transitive lockfile, quiet build, and frozen mode`() {
        val projectDir = tempDir.resolve("phase2-app")

        // 1. Initialize project
        val initResult = execute(listOf("init", projectDir.toString()))
        assertEquals(0, initResult.exitCode, initResult.stderr)
        assertTrue(initResult.stdout.contains("Initialized"))
        assertTrue(initResult.stdout.contains("✨"))

        // 2. Add dependency with real transitive dependencies (kotlinx-coroutines-core)
        val addResult = execute(listOf("add", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), projectDir)
        assertEquals(0, addResult.exitCode, addResult.stderr)
        assertTrue(addResult.stdout.contains("Added org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), addResult.stdout)
        assertTrue(addResult.stdout.contains("➕"))

        val manifestContent = Files.readString(projectDir.resolve("qutivex.toml"))
        assertTrue(manifestContent.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core"))

        val lockFile = projectDir.resolve("qutivex.lock")
        assertTrue(Files.exists(lockFile))
        val lockContent = Files.readString(lockFile)
        assertTrue(lockContent.contains("[[package]]"), "Lockfile must contain [[package]] format")
        assertTrue(lockContent.contains("kotlinx-coroutines-core"), "Lockfile must contain direct dependency")
        assertTrue(lockContent.contains("kotlinx-coroutines-core-jvm") || lockContent.contains("kotlin-stdlib"), "Lockfile must contain transitive dependency")
        assertTrue(lockContent.contains("direct = true"), "Lockfile must distinguish direct dependencies")

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
        assertTrue(runResult.stdout.contains("Finished in"), runResult.stdout)

        // 5. Test quiet build (Gradle noise suppressed)
        val quietBuildResult = execute(listOf("build"), projectDir)
        assertEquals(0, quietBuildResult.exitCode, quietBuildResult.stderr)
        assertTrue(quietBuildResult.stdout.contains("Building phase2-app"), quietBuildResult.stdout)
        assertTrue(quietBuildResult.stdout.contains("Build completed"), quietBuildResult.stdout)
        assertFalse(quietBuildResult.stdout.contains("> Task :compileKotlin"), "Quiet build must not display Gradle task noise")
        assertFalse(quietBuildResult.stdout.contains("BUILD SUCCESSFUL"), "Quiet build must not display Gradle banner")

        // 6. Test verbose build (Gradle details visible)
        val verboseBuildResult = execute(listOf("build", "--verbose"), projectDir)
        assertEquals(0, verboseBuildResult.exitCode, verboseBuildResult.stderr)
        assertTrue(verboseBuildResult.stdout.contains("Building phase2-app"), verboseBuildResult.stdout)
        assertTrue(verboseBuildResult.stdout.contains("Build completed"), verboseBuildResult.stdout)

        // 7. List dependencies
        val listResult = execute(listOf("list"), projectDir)
        assertEquals(0, listResult.exitCode, listResult.stderr)
        assertTrue(listResult.stdout.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), listResult.stdout)
        assertTrue(listResult.stdout.contains("📋 Dependencies"), listResult.stdout)

        // 8. Install --frozen succeeds when lockfile matches
        val installFrozenResult = execute(listOf("install", "--frozen"), projectDir)
        assertEquals(0, installFrozenResult.exitCode, installFrozenResult.stderr)
        assertTrue(installFrozenResult.stdout.contains("Dependencies installed"), installFrozenResult.stdout)

        // 9. Tamper with qutivex.toml and verify install --frozen rejects mismatch
        val tamperedManifest = manifestContent.replace("1.10.2", "1.9.0")
        Files.writeString(projectDir.resolve("qutivex.toml"), tamperedManifest)

        val frozenMismatchResult = execute(listOf("install", "--frozen"), projectDir)
        assertEquals(1, frozenMismatchResult.exitCode)
        assertTrue(
            frozenMismatchResult.stderr.contains("Lockfile is out of sync") ||
            frozenMismatchResult.stderr.contains("Lockfile direct dependencies do not match"),
            frozenMismatchResult.stderr,
        )

        // Restore valid manifest
        Files.writeString(projectDir.resolve("qutivex.toml"), manifestContent)

        // 10. Invalid dependency rollback test
        val badAddResult = execute(listOf("add", "com.fake.doesnotexist:nothing:999.0.0"), projectDir)
        assertEquals(1, badAddResult.exitCode)
        assertTrue(badAddResult.stderr.contains("Failed to resolve dependency"), badAddResult.stderr)
        // Manifest must NOT have the invalid dependency
        val currentManifest = Files.readString(projectDir.resolve("qutivex.toml"))
        assertFalse(currentManifest.contains("com.fake.doesnotexist:nothing"))

        // 11. Remove dependency
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

        // 12. Run again to ensure removal took effect cleanly
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
