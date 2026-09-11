package dev.qutivex.cli

import dev.qutivex.engine.backend.gradle.BackendProcessRunner
import dev.qutivex.engine.project.ProjectExecutor
import dev.qutivex.engine.project.ProjectInitializer
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QutivexCliTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `help works without creating or requiring a project directory`() {
        val missingDirectory = temporaryDirectory.resolve("missing")
        for (args in listOf(emptyList(), listOf("--help"), listOf("-h"), listOf("help"))) {
            val result = execute(args, missingDirectory)

            assertEquals(0, result.exitCode, args.toString())
            assertTrue(result.stdout.contains("Usage: qutivex"))
            assertTrue(result.stdout.contains("init [directory]"))
            assertTrue(result.stdout.contains("run [-- args]"))
            assertTrue(result.stdout.contains("test"))
            assertTrue(result.stdout.contains("build"))
            assertTrue(result.stdout.contains("doctor"))
            assertTrue(result.stdout.contains("Planned commands (not implemented yet): add, install."))
            assertEquals("", result.stderr)
            assertFalse(Files.exists(missingDirectory))
        }
    }

    @Test
    fun `doctor help has no filesystem effects`() {
        for (flag in listOf("--help", "-h")) {
            val result = execute(listOf("doctor", flag))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage: qutivex doctor"))
            assertEquals("", result.stderr)
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `doctor runs without requiring a project directory`() {
        val missingDirectory = temporaryDirectory.resolve("missing")
        val result = execute(listOf("doctor"), missingDirectory)

        assertTrue(result.stdout.contains("Qutivex Environment"))
        assertTrue(result.stdout.contains("Qutivex"))
        assertTrue(result.stdout.contains("Platform"))
        assertFalse(Files.exists(missingDirectory))
    }

    @Test
    fun `init help has no filesystem effects`() {
        for (flag in listOf("--help", "-h")) {
            val result = execute(listOf("init", flag))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage: qutivex init"))
            assertEquals("", result.stderr)
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `run help has no filesystem effects`() {
        for (flag in listOf("--help", "-h")) {
            val result = execute(listOf("run", flag))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage: qutivex run"))
            assertEquals("", result.stderr)
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `test help has no filesystem effects`() {
        for (flag in listOf("--help", "-h")) {
            val result = execute(listOf("test", flag))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage: qutivex test"))
            assertEquals("", result.stderr)
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `build help has no filesystem effects`() {
        for (flag in listOf("--help", "-h")) {
            val result = execute(listOf("build", flag))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage: qutivex build"))
            assertEquals("", result.stderr)
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `version comes from build metadata without requiring a project`() {
        val metadata = Properties()
        val resource = requireNotNull(javaClass.getResourceAsStream("/qutivex-version.properties"))
        resource.use(metadata::load)
        val expectedVersion = requireNotNull(metadata.getProperty("version"))
        val missingDirectory = temporaryDirectory.resolve("missing")

        for (flag in listOf("--version", "-V")) {
            val result = execute(listOf(flag), missingDirectory)

            assertEquals(0, result.exitCode)
            assertEquals("Qutivex $expectedVersion${System.lineSeparator()}", result.stdout)
            assertEquals("", result.stderr)
            assertFalse(Files.exists(missingDirectory))
        }
    }

    @Test
    fun `init creates a project using a relative directory containing spaces`() {
        val result = execute(listOf("init", "my app"))
        val projectDirectory = temporaryDirectory.resolve("my app")

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("Initialized"))
        assertEquals("", result.stderr)
        assertTrue(Files.isRegularFile(projectDirectory.resolve("qutivex.toml")))
        assertTrue(Files.isRegularFile(projectDirectory.resolve("src/main/kotlin/Main.kt")))
        assertTrue(Files.readString(projectDirectory.resolve("src/main/kotlin/Main.kt")).contains("fun main"))
    }

    @Test
    fun `init defaults to the supplied working directory`() {
        val projectDirectory = Files.createDirectory(temporaryDirectory.resolve("current-project"))
        val result = execute(listOf("init"), projectDirectory)

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(Files.isRegularFile(projectDirectory.resolve("qutivex.toml")))
    }

    @Test
    fun `init accepts an absolute destination`() {
        val destination = temporaryDirectory.resolve("absolute-project").toAbsolutePath()
        val result = execute(listOf("init", destination.toString()), temporaryDirectory.resolve("unused"))

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(Files.isRegularFile(destination.resolve("qutivex.toml")))
        assertFalse(Files.exists(temporaryDirectory.resolve("unused")))
    }

    @Test
    fun `init accepts a directory after the option delimiter`() {
        val result = execute(listOf("init", "--", "my-app"))

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("my-app/qutivex.toml")))
    }

    @Test
    fun `delimiter passes dash-prefixed directory names to project validation`() {
        val result = execute(listOf("init", "--", "-my-app"))

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Project name must start with a lowercase letter"))
        assertFalse(result.stderr.contains("Unknown option"))
        assertFalse(Files.exists(temporaryDirectory.resolve("-my-app")))
    }

    @Test
    fun `init refuses to overwrite user files and returns an operation failure`() {
        val projectDirectory = Files.createDirectory(temporaryDirectory.resolve("existing-project"))
        val existingFile = projectDirectory.resolve("qutivex.toml")
        val contents = "# User-owned configuration\nname = \"keep-me\"\n"
        Files.writeString(existingFile, contents)

        val result = execute(listOf("init"), projectDirectory)

        assertEquals(1, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.startsWith("error: "))
        assertFalse(result.stderr.contains("\tat "))
        assertEquals(contents, Files.readString(existingFile))
        Files.list(projectDirectory).use { assertEquals(1L, it.count()) }
    }

    @Test
    fun `invalid arguments fail before creating files`() {
        val invalidArguments = listOf(
            listOf("unknown"),
            listOf("--unknown"),
            listOf("help", "extra"),
            listOf("--help", "extra"),
            listOf("--version", "extra"),
            listOf("init", "one", "two"),
            listOf("init", "--force"),
            listOf("init", "one", "--force"),
            listOf("init", "--help", "one"),
            listOf("init", ""),
            listOf("init", "--", "one", "two"),
            listOf("run", "--unknown"),
            listOf("run", "unexpected"),
            listOf("run", "--unknown", "--", "arg"),
            listOf("test", "extra"),
            listOf("test", "--unknown"),
            listOf("build", "extra"),
            listOf("build", "--unknown"),
            listOf("doctor", "extra"),
            listOf("doctor", "--unknown"),
        )
        for (args in invalidArguments) {
            val result = execute(args)

            assertEquals(2, result.exitCode, args.toString())
            assertEquals("", result.stdout, args.toString())
            assertTrue(result.stderr.startsWith("error: "), args.toString())
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count(), args.toString()) }
        }
    }

    @Test
    fun `planned commands return failure with an honest explanation`() {
        for (command in listOf("add", "install")) {
            val result = execute(listOf(command))

            assertEquals(2, result.exitCode, command)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.contains("not implemented yet"))
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `run delegates forwarded arguments to ProjectExecutor`() {
        // Initialize project first
        execute(listOf("init"), temporaryDirectory)

        val recordingRunner = RecordingRunner()
        val cli = QutivexCli(
            projectExecutor = ProjectExecutor(processRunner = recordingRunner),
        )

        val stdout = StringWriter()
        val stderr = StringWriter()
        val exitCode = cli.execute(
            listOf("run", "--", "foo", "bar"),
            temporaryDirectory,
            PrintWriter(stdout),
            PrintWriter(stderr),
        )

        assertEquals(0, exitCode)
        assertEquals(listOf("run"), recordingRunner.recordedTasks)
        val argsFile = temporaryDirectory.resolve(".qutivex/gradle/application-args.txt")
        assertTrue(Files.exists(argsFile))
    }

    @Test
    fun `test and build delegate to ProjectExecutor`() {
        execute(listOf("init"), temporaryDirectory)

        val recordingRunner = RecordingRunner()
        val cli = QutivexCli(
            projectExecutor = ProjectExecutor(processRunner = recordingRunner),
        )

        val stdout = StringWriter()
        val stderr = StringWriter()

        val testExit = cli.execute(listOf("test"), temporaryDirectory, PrintWriter(stdout), PrintWriter(stderr))
        assertEquals(0, testExit)
        assertEquals(listOf("test"), recordingRunner.recordedTasks)

        val buildExit = cli.execute(listOf("build"), temporaryDirectory, PrintWriter(stdout), PrintWriter(stderr))
        assertEquals(0, buildExit)
        assertEquals(listOf("build"), recordingRunner.recordedTasks)
    }

    @Test
    fun `run reports error when manifest is missing`() {
        val emptyDir = temporaryDirectory.resolve("empty")
        Files.createDirectories(emptyDir)

        val result = execute(listOf("run"), emptyDir)
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("No 'qutivex.toml' manifest found"))
    }

    private fun execute(args: List<String>, workingDirectory: Path = temporaryDirectory): Result {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val exitCode = QutivexCli().execute(args, workingDirectory, PrintWriter(stdout), PrintWriter(stderr))
        return Result(exitCode, stdout.toString(), stderr.toString())
    }

    private class RecordingRunner : BackendProcessRunner {
        var recordedTasks: List<String> = emptyList()
        var recordedExtraArgs: List<String> = emptyList()

        override fun execute(
            projectDir: Path,
            tasks: List<String>,
            extraArgs: List<String>,
            stdout: PrintWriter,
            stderr: PrintWriter,
            stdin: InputStream?,
        ): Int {
            recordedTasks = tasks
            recordedExtraArgs = extraArgs
            return 0
        }
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)
}
