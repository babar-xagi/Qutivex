package dev.qutivex.cli

import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase4IntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val cli = QutivexCli()

    @Test
    fun `end-to-end native build engine - zero gradle run test build and jar execution`() {
        val projectDir = tempDir.resolve("phase4-native-app")

        // 1. Initialize project
        val initResult = execute(listOf("init", projectDir.toString()))
        assertEquals(0, initResult.exitCode, initResult.stderr)
        assertTrue(initResult.stdout.contains("Initialized"))

        // Deliberately corrupt any Gradle wrapper to prove Gradle is never executed
        val gradleDir = projectDir.resolve(".qutivex/gradle")
        if (Files.exists(gradleDir)) {
            val gradlewBat = gradleDir.resolve("gradlew.bat")
            if (Files.exists(gradlewBat)) {
                Files.writeString(gradlewBat, "@echo off\necho GRADLE WAS CALLED WHEN IT SHOULD NOT BE\nexit /b 1\n")
            }
        }

        // 2. Native run (fresh scaffold)
        val run1 = execute(listOf("run"), projectDir)
        assertEquals(0, run1.exitCode, run1.stderr)
        assertTrue(run1.stdout.contains("Hello from phase4-native-app!"), run1.stdout)
        assertFalse(run1.stdout.contains("GRADLE WAS CALLED"), "Gradle must not be invoked")
        assertFalse(run1.stderr.contains("GRADLE WAS CALLED"), "Gradle must not be invoked")

        // 3. Incremental run (no changes - UP-TO-DATE fast path)
        val startWarm = System.currentTimeMillis()
        val runWarm = execute(listOf("run"), projectDir)
        val warmDuration = System.currentTimeMillis() - startWarm
        assertEquals(0, runWarm.exitCode, runWarm.stderr)
        assertTrue(runWarm.stdout.contains("Hello from phase4-native-app!"), runWarm.stdout)
        assertTrue(warmDuration < 3000, "Warm run must be fast (< 3s), took ${warmDuration}ms")

        // 4. Run with argument forwarding
        val mainFile = projectDir.resolve("src/main/kotlin/Main.kt")
        Files.writeString(
            mainFile,
            """
                fun main(args: Array<String>) {
                    println("Args: " + args.joinToString(" | "))
                }
            """.trimIndent(),
        )

        val runArgs = execute(listOf("run", "--", "first", "second with space"), projectDir)
        assertEquals(0, runArgs.exitCode, runArgs.stderr)
        assertTrue(runArgs.stdout.contains("Args: first | second with space"), runArgs.stdout)

        // 5. Native test execution (JUnit platform)
        val testFile = projectDir.resolve("src/test/kotlin/SampleTest.kt")
        Files.createDirectories(testFile.parent)
        Files.writeString(
            testFile,
            """
                import kotlin.test.Test
                import kotlin.test.assertEquals

                class SampleTest {
                    @Test
                    fun `addition works`() {
                        assertEquals(42, 40 + 2)
                    }
                }
            """.trimIndent(),
        )

        val testResult = execute(listOf("test"), projectDir)
        assertEquals(0, testResult.exitCode, testResult.stderr)
        assertTrue(testResult.stdout.contains("PASSED") || testResult.stdout.contains("Tests passed"), testResult.stdout)

        // 6. Test failure behavior
        val failingTest = projectDir.resolve("src/test/kotlin/BrokenTest.kt")
        Files.writeString(
            failingTest,
            """
                import kotlin.test.Test
                import kotlin.test.fail

                class BrokenTest {
                    @Test
                    fun `must fail`() {
                        fail("Expected test failure")
                    }
                }
            """.trimIndent(),
        )

        val failResult = execute(listOf("test"), projectDir)
        assertEquals(1, failResult.exitCode)
        assertTrue(failResult.stdout.contains("FAILED") || failResult.stderr.contains("FAILED"))

        // Remove failing test
        Files.delete(failingTest)

        // 7. Native build (compiles, runs tests, packages runnable JAR)
        val buildResult = execute(listOf("build"), projectDir)
        assertEquals(0, buildResult.exitCode, buildResult.stderr)
        assertTrue(buildResult.stdout.contains("Building phase4-native-app"), buildResult.stdout)
        assertTrue(buildResult.stdout.contains("Build completed") || buildResult.stdout.contains("Packaged"), buildResult.stdout)

        val outputJar = projectDir.resolve("build/libs/phase4-native-app-0.1.0.jar")
        assertTrue(Files.exists(outputJar), "Packaged JAR must exist at $outputJar")
        assertTrue(Files.size(outputJar) > 0, "Packaged JAR must not be empty")

        // 8. Execute the packaged JAR via java -jar (verifying manifest Main-Class)
        val javaExe = resolveJavaExecutable()
        val jarProcess = ProcessBuilder(
            listOf(javaExe, "-jar", outputJar.toAbsolutePath().toString(), "from-packaged-jar")
        ).redirectErrorStream(true).start()

        val jarOutput = BufferedReader(InputStreamReader(jarProcess.inputStream, UTF_8)).use { it.readText() }
        val jarExit = jarProcess.waitFor()
        assertEquals(0, jarExit, "Packaged jar should execute with code 0: $jarOutput")
        assertTrue(jarOutput.contains("Args: from-packaged-jar"), "Jar output was: $jarOutput")

        // 9. Add dependency and run natively
        val addResult = execute(listOf("add", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), projectDir)
        assertEquals(0, addResult.exitCode, addResult.stderr)

        Files.writeString(
            mainFile,
            """
                import kotlinx.coroutines.runBlocking

                fun main() = runBlocking {
                    println("Native Coroutines Succeeded!")
                }
            """.trimIndent(),
        )

        val runDep = execute(listOf("run"), projectDir)
        assertEquals(0, runDep.exitCode, runDep.stderr)
        assertTrue(runDep.stdout.contains("Native Coroutines Succeeded!"), runDep.stdout)

        // Ensure Gradle was NEVER called throughout the entire session
        assertFalse(runDep.stdout.contains("GRADLE WAS CALLED"))
        assertFalse(runDep.stderr.contains("GRADLE WAS CALLED"))
    }

    private fun execute(args: List<String>, workingDirectory: Path = tempDir): Result {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val exitCode = cli.execute(args, workingDirectory, PrintWriter(stdout), PrintWriter(stderr))
        return Result(exitCode, stdout.toString(), stderr.toString())
    }

    private fun resolveJavaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        if (!javaHome.isNullOrBlank()) {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val binName = if (isWindows) "java.exe" else "java"
            val javaPath = Path.of(javaHome, "bin", binName)
            if (Files.exists(javaPath)) {
                return javaPath.toAbsolutePath().toString()
            }
        }
        return "java"
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)
}
