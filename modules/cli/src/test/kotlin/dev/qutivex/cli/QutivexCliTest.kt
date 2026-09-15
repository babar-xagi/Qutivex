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
            assertTrue(result.stdout.contains("add <dep>"))
            assertTrue(result.stdout.contains("remove <dep>"))
            assertTrue(result.stdout.contains("list"))
            assertTrue(result.stdout.contains("install"))
            assertTrue(result.stdout.contains("doctor"))
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
    fun `add help has no filesystem effects`() {
        for (flag in listOf("--help", "-h")) {
            val result = execute(listOf("add", flag))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage: qutivex add"))
            assertEquals("", result.stderr)
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `remove help has no filesystem effects`() {
        for (flag in listOf("--help", "-h")) {
            val result = execute(listOf("remove", flag))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage: qutivex remove"))
            assertEquals("", result.stderr)
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `list help has no filesystem effects`() {
        for (flag in listOf("--help", "-h")) {
            val result = execute(listOf("list", flag))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage: qutivex list"))
            assertEquals("", result.stderr)
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `install help has no filesystem effects`() {
        for (flag in listOf("--help", "-h")) {
            val result = execute(listOf("install", flag))

            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage: qutivex install"))
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
    fun `formatDuration formats millis, seconds, and minutes correctly`() {
        assertEquals("0ms", QutivexCli.formatDuration(0))
        assertEquals("42ms", QutivexCli.formatDuration(42))
        assertEquals("999ms", QutivexCli.formatDuration(999))
        assertEquals("1.00s", QutivexCli.formatDuration(1000))
        assertEquals("1.50s", QutivexCli.formatDuration(1500))
        assertEquals("1m 5.0s", QutivexCli.formatDuration(65000))
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
    fun `init succeeds in an existing empty directory`() {
        val emptyDir = Files.createDirectory(temporaryDirectory.resolve("empty-app"))
        val result = execute(listOf("init"), emptyDir)

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(Files.isRegularFile(emptyDir.resolve("qutivex.toml")))
    }

    @Test
    fun `init accepts exactly 64-character normalized project name`() {
        val name64 = "a".repeat(64)
        val target = temporaryDirectory.resolve(name64)
        val result = execute(listOf("init", name64))

        assertEquals(0, result.exitCode, result.stderr)
        val manifest = Files.readString(target.resolve("qutivex.toml"))
        assertTrue(manifest.contains("name = \"$name64\""))
    }

    @Test
    fun `init rejects greater than 64-character project name with exit code 1`() {
        val name65 = "a".repeat(65)
        val result = execute(listOf("init", name65))

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Project name must start with a lowercase letter and contain only lowercase ASCII letters, digits, or hyphens (1–64 characters)"))
        assertFalse(Files.exists(temporaryDirectory.resolve(name65)))
    }

    @Test
    fun `init normalizes spaces, uppercase, and underscores into lowercase hyphenated manifest name`() {
        val cases = listOf(
            "Hello World" to "hello-world",
            "UPPERCASE" to "uppercase",
            "bad_name" to "bad-name",
            "hello-123" to "hello-123",
            "my   cool__app" to "my-cool-app",
        )

        for ((dirName, expectedManifestName) in cases) {
            val target = temporaryDirectory.resolve(dirName)
            val result = execute(listOf("init", dirName))

            assertEquals(0, result.exitCode, "Failed for $dirName: ${result.stderr}")
            assertTrue(Files.isDirectory(target), "Directory should exist on disk: $target")
            val manifest = Files.readString(target.resolve("qutivex.toml"))
            assertTrue(
                manifest.contains("name = \"$expectedManifestName\""),
                "Manifest for $dirName should have name = \"$expectedManifestName\", got:\n$manifest",
            )
        }
    }

    @Test
    fun `init rejects invalid-only names with exit code 1`() {
        val invalidNames = listOf("___", "---", "!!!", "@@@", "2start", "éclair")
        for (name in invalidNames) {
            val result = execute(listOf("init", "--", name))

            assertEquals(1, result.exitCode, "Expected failure for '$name': ${result.stderr}")
            assertTrue(result.stderr.startsWith("error: "))
            assertFalse(Files.exists(temporaryDirectory.resolve(name)))
        }
    }

    @Test
    fun `init preserves directory name on disk while normalizing manifest project-name`() {
        val dirName = "My Special App"
        val target = temporaryDirectory.resolve(dirName)
        val result = execute(listOf("init", dirName))

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(Files.exists(target))
        assertEquals(dirName, target.fileName.toString())
        val manifest = Files.readString(target.resolve("qutivex.toml"))
        assertTrue(manifest.contains("name = \"my-special-app\""))
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
            listOf("add", "--unknown"),
            listOf("add", "group:artifact:1.0", "extra"),
            listOf("remove", "--unknown"),
            listOf("remove", "group:artifact", "extra"),
            listOf("list", "extra"),
            listOf("list", "--unknown"),
            listOf("tree", "--unknown"),
            listOf("tree", "--scope=invalid"),
            listOf("tree", "--depth=invalid"),
            listOf("install", "extra"),
            listOf("install", "--unknown"),
            listOf("toolchain", "unknown"),
            listOf("toolchain", "list", "invalid"),
            listOf("toolchain", "list", "extra1", "extra2"),
            listOf("toolchain", "install"),
            listOf("toolchain", "install", "invalid", "1.0"),
            listOf("toolchain", "use"),
            listOf("toolchain", "use", "invalid", "1.0"),
            listOf("toolchain", "remove"),
            listOf("toolchain", "remove", "invalid", "1.0"),
            listOf("toolchain", "update", "invalid"),
            listOf("env", "unknown"),
            listOf("env", "info", "extra"),
            listOf("env", "clean", "extra"),
            listOf("env", "recreate", "extra"),
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
    fun `add and remove require dependency coordinates`() {
        for (command in listOf("add", "remove")) {
            val result = execute(listOf(command))

            assertEquals(2, result.exitCode, command)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.contains("Missing dependency coordinate"))
            Files.list(temporaryDirectory).use { assertEquals(0L, it.count()) }
        }
    }

    @Test
    fun `run delegates forwarded arguments to ProjectExecutor`() {
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
    fun `exit-code contract - successful commands and help return 0`() {
        val successCommands = listOf(
            emptyList(),
            listOf("help"),
            listOf("--help"),
            listOf("-h"),
            listOf("init", "--help"),
            listOf("run", "--help"),
            listOf("test", "--help"),
            listOf("build", "--help"),
            listOf("add", "--help"),
            listOf("remove", "--help"),
            listOf("list", "--help"),
            listOf("tree", "--help"),
            listOf("install", "--help"),
            listOf("toolchain"),
            listOf("toolchain", "--help"),
            listOf("env"),
            listOf("env", "--help"),
            listOf("doctor"),
            listOf("doctor", "--help"),
            listOf("--version"),
            listOf("-V"),
        )

        for (cmd in successCommands) {
            val result = execute(cmd)
            assertEquals(0, result.exitCode, "Expected exit code 0 for $cmd: ${result.stderr}")
        }
    }

    @Test
    fun `exit-code contract - operational failures return 1`() {
        val emptyDir = temporaryDirectory.resolve("empty-operational")
        Files.createDirectories(emptyDir)

        val operationalFailures = listOf(
            listOf("run"),
            listOf("test"),
            listOf("build"),
            listOf("add", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0"),
            listOf("remove", "org.jetbrains.kotlinx:kotlinx-coroutines-core"),
            listOf("tree"),
            listOf("install"),
            listOf("toolchain", "use", "kotlin", "9.9.9"),
            listOf("toolchain", "use", "jdk", "99"),
            listOf("env", "info"),
            listOf("env", "clean"),
            listOf("env", "recreate"),
        )

        for (cmd in operationalFailures) {
            val result = execute(cmd, emptyDir)
            assertEquals(1, result.exitCode, "Expected exit code 1 for $cmd, got ${result.exitCode}")
            assertTrue(result.stderr.startsWith("error: "), "Expected error prefix for $cmd: ${result.stderr}")
        }
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
