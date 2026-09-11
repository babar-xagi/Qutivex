package dev.qutivex.engine.project

import dev.qutivex.engine.backend.gradle.BackendProcessRunner
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectExecutorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fails with actionable error when qutivex toml is missing`() {
        val executor = ProjectExecutor()
        val stdout = PrintWriter(StringWriter())
        val stderr = PrintWriter(StringWriter())

        val ex = assertFailsWith<IllegalArgumentException> {
            executor.run(tempDir, emptyList(), stdout, stderr)
        }
        assertTrue(ex.message!!.contains("No 'qutivex.toml' manifest found"))
    }

    @Test
    fun `orchestrates preparation and passes arguments to process runner`() {
        Files.writeString(
            tempDir.resolve("qutivex.toml"),
            """
                schema-version = 1
                [project]
                name = "test-proj"
                [toolchain]
                kotlin = "2.4.10"
                jvm = 21
                [application]
                main-class = "MainKt"
            """.trimIndent(),
        )

        val testWriter = StringWriter()
        val writer = PrintWriter(testWriter)
        val recordingRunner = RecordingRunner()
        val recordingExecutor = ProjectExecutor(processRunner = recordingRunner)

        val exitCode = recordingExecutor.run(tempDir, listOf("arg1", "arg2 with space"), writer, writer)
        assertEquals(0, exitCode)
        val argsFile = tempDir.resolve(".qutivex/gradle/application-args.txt")
        assertTrue(Files.exists(argsFile))
        assertTrue(Files.exists(tempDir.resolve(".qutivex/gradle/build.gradle.kts")))

        // Test `test` task
        recordingExecutor.test(tempDir, writer, writer)
        assertEquals(listOf("test"), recordingRunner.recordedTasks)

        // Test `build` task
        recordingExecutor.build(tempDir, writer, writer)
        assertEquals(listOf("build"), recordingRunner.recordedTasks)
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
}
