package dev.qutivex.cli

import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase1IntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val cli = QutivexCli()

    @Test
    fun `end-to-end init, run with forwarded arguments, test, and build in directory with spaces`() {
        val projectDir = tempDir.resolve("demo app")

        // 1. Initialize project
        val initResult = execute(listOf("init", projectDir.toString()))
        assertEquals(0, initResult.exitCode, initResult.stderr)
        assertTrue(Files.exists(projectDir.resolve("qutivex.toml")))

        // 2. Run initial project
        val runResult = execute(listOf("run"), projectDir)
        assertEquals(0, runResult.exitCode, runResult.stderr)
        assertTrue(runResult.stdout.contains("Hello from demo-app!"), runResult.stdout)

        // 3. Run with argument forwarding
        // Update Main.kt to print command line arguments
        val mainFile = projectDir.resolve("src/main/kotlin/Main.kt")
        Files.writeString(
            mainFile,
            """
                fun main(args: Array<String>) {
                    println("Received: " + args.joinToString(", "))
                }
            """.trimIndent(),
        )

        val runArgsResult = execute(listOf("run", "--", "alpha", "beta with space"), projectDir)
        assertEquals(0, runArgsResult.exitCode, runArgsResult.stderr)
        assertTrue(runArgsResult.stdout.contains("Received: alpha, beta with space"), runArgsResult.stdout)

        // 4. Test passing test
        val testFile = projectDir.resolve("src/test/kotlin/AppTest.kt")
        Files.writeString(
            testFile,
            """
                import kotlin.test.Test
                import kotlin.test.assertEquals

                class AppTest {
                    @Test
                    fun `test passes`() {
                        assertEquals(4, 2 + 2)
                    }
                }
            """.trimIndent(),
        )

        val passTestResult = execute(listOf("test"), projectDir)
        assertEquals(0, passTestResult.exitCode, passTestResult.stderr)
        assertTrue(passTestResult.stdout.contains("passed") || passTestResult.stdout.contains("SUCCESS"), passTestResult.stdout)

        // 5. Test failing test
        val failingTestFile = projectDir.resolve("src/test/kotlin/FailingTest.kt")
        Files.writeString(
            failingTestFile,
            """
                import kotlin.test.Test
                import kotlin.test.fail

                class FailingTest {
                    @Test
                    fun `test fails deliberately`() {
                        fail("Deliberate test failure")
                    }
                }
            """.trimIndent(),
        )

        val failTestResult = execute(listOf("test"), projectDir)
        assertEquals(1, failTestResult.exitCode)
        assertTrue(failTestResult.stdout.contains("failed") || failTestResult.stdout.contains("FAILURE") || failTestResult.stderr.contains("FAILURE"))

        // Remove failing test so build can succeed
        Files.delete(failingTestFile)

        // 6. Build project distribution
        val buildResult = execute(listOf("build"), projectDir)
        assertEquals(0, buildResult.exitCode, buildResult.stderr)
        assertTrue(Files.exists(projectDir.resolve("build")))

        // 7. Add real transitive dependency and verify resolution
        val manifestFile = projectDir.resolve("qutivex.toml")
        Files.writeString(
            manifestFile,
            """
                schema-version = 1

                [project]
                name = "demo-app"
                version = "0.1.0"

                [toolchain]
                kotlin = "2.4.10"
                jvm = 21

                [application]
                main-class = "MainKt"

                [dependencies]
                "org.jetbrains.kotlinx:kotlinx-coroutines-core" = "1.10.2"

                [test-dependencies]
            """.trimIndent(),
        )

        Files.writeString(
            mainFile,
            """
                import kotlinx.coroutines.runBlocking

                fun main() = runBlocking {
                    println("Coroutines running!")
                }
            """.trimIndent(),
        )

        val runCoroutinesResult = execute(listOf("run"), projectDir)
        assertEquals(0, runCoroutinesResult.exitCode, runCoroutinesResult.stderr)
        assertTrue(runCoroutinesResult.stdout.contains("Coroutines running!"), runCoroutinesResult.stdout)
    }

    private fun execute(args: List<String>, workingDirectory: Path = tempDir): Result {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val exitCode = cli.execute(args, workingDirectory, PrintWriter(stdout), PrintWriter(stderr))
        return Result(exitCode, stdout.toString(), stderr.toString())
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)
}
