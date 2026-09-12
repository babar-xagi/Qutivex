package dev.qutivex.engine.project

import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.backend.gradle.BackendProcessRunner
import dev.qutivex.engine.backend.gradle.GradleBackendGenerator
import dev.qutivex.engine.backend.gradle.GradleProcessRunner
import dev.qutivex.engine.build.NativeBuildEngine
import dev.qutivex.engine.manifest.ManifestParser
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Orchestrates manifest parsing and project execution.
 * Uses NativeBuildEngine by default for completely Gradle-free, high-performance execution.
 */
class ProjectExecutor(
    private val manifestParser: ManifestParser = ManifestParser(),
    private val backendGenerator: GradleBackendGenerator = GradleBackendGenerator(),
    private val processRunner: BackendProcessRunner = GradleProcessRunner(),
    private val nativeBuildEngine: NativeBuildEngine = NativeBuildEngine(manifestParser = manifestParser),
) {
    /** True if a custom non-default process runner was injected (e.g., in unit tests). */
    private val isLegacyRunner: Boolean = processRunner !is GradleProcessRunner

    fun run(
        projectDir: Path,
        args: List<String> = emptyList(),
        stdout: PrintWriter,
        stderr: PrintWriter,
        stdin: InputStream? = null,
        verbose: Boolean = false,
    ): Int {
        if (!isLegacyRunner) {
            return nativeBuildEngine.run(
                projectDir = projectDir,
                args = args,
                stdout = stdout,
                stderr = stderr,
                stdin = stdin,
                verbose = verbose,
            )
        }

        val manifest = prepare(projectDir)
        val argsFile = projectDir.resolve(".qutivex/gradle/application-args.txt")
        if (args.isNotEmpty()) {
            val encoded = args.joinToString("\n") {
                java.util.Base64.getEncoder().encodeToString(it.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            }
            Files.writeString(argsFile, encoded, java.nio.charset.StandardCharsets.UTF_8)
        } else {
            Files.deleteIfExists(argsFile)
        }
        val extraArgs = if (verbose) {
            listOf("--console=plain", "--build-cache")
        } else {
            listOf("--quiet", "--build-cache")
        }
        return processRunner.execute(
            projectDir = projectDir,
            tasks = listOf("run"),
            extraArgs = extraArgs,
            stdout = stdout,
            stderr = stderr,
            stdin = stdin,
        )
    }

    fun test(
        projectDir: Path,
        stdout: PrintWriter,
        stderr: PrintWriter,
        verbose: Boolean = false,
    ): Int {
        if (!isLegacyRunner) {
            return nativeBuildEngine.test(
                projectDir = projectDir,
                stdout = stdout,
                stderr = stderr,
                verbose = verbose,
            )
        }

        prepare(projectDir)
        val extraArgs = listOf("--console=plain", "--build-cache")
        if (verbose) {
            return processRunner.execute(
                projectDir = projectDir,
                tasks = listOf("test"),
                extraArgs = extraArgs,
                stdout = stdout,
                stderr = stderr,
            )
        }

        val outCapture = StringWriter()
        val errCapture = StringWriter()
        val exitCode = processRunner.execute(
            projectDir = projectDir,
            tasks = listOf("test"),
            extraArgs = extraArgs,
            stdout = PrintWriter(outCapture),
            stderr = PrintWriter(errCapture),
        )

        val outLines = outCapture.toString().lines()
        outLines.forEach { line ->
            val trimmed = line.trim()
            if (isTestEventLine(trimmed)) {
                stdout.println(line)
            }
        }

        if (exitCode != 0) {
            val errContent = errCapture.toString().trim()
            if (errContent.isNotBlank()) {
                stderr.println(errContent)
            } else {
                val failLines = outLines.filter {
                    it.contains("FAILED") || it.contains("FAILURE") || it.contains("Error")
                }
                if (failLines.isNotEmpty()) {
                    failLines.forEach { stderr.println(it) }
                }
            }
        }

        return exitCode
    }

    fun build(
        projectDir: Path,
        stdout: PrintWriter,
        stderr: PrintWriter,
        verbose: Boolean = false,
    ): Int {
        if (!isLegacyRunner) {
            return nativeBuildEngine.build(
                projectDir = projectDir,
                stdout = stdout,
                stderr = stderr,
                verbose = verbose,
            )
        }

        prepare(projectDir)
        val extraArgs = listOf("--console=plain", "--build-cache")
        if (verbose) {
            return processRunner.execute(
                projectDir = projectDir,
                tasks = listOf("build"),
                extraArgs = extraArgs,
                stdout = stdout,
                stderr = stderr,
            )
        }

        val outCapture = StringWriter()
        val errCapture = StringWriter()
        val exitCode = processRunner.execute(
            projectDir = projectDir,
            tasks = listOf("build"),
            extraArgs = extraArgs,
            stdout = PrintWriter(outCapture),
            stderr = PrintWriter(errCapture),
        )

        if (exitCode != 0) {
            val errContent = errCapture.toString().trim()
            val outContent = outCapture.toString().trim()
            val diagnostic = errContent.ifBlank { outContent }
            if (diagnostic.isNotBlank()) {
                stderr.println(diagnostic)
            }
        }

        return exitCode
    }

    private fun isTestEventLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.startsWith("> Task") || line.startsWith("BUILD ") || line.startsWith("Consider enabling")) return false
        return line.contains("PASSED") || line.contains("FAILED") || line.contains("SKIPPED") || line.contains("SUCCESS")
    }

    private fun prepare(projectDir: Path): ManifestSpec {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw IllegalArgumentException(
                "No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.",
            )
        }
        val manifest = manifestParser.parse(manifestFile)
        backendGenerator.generate(projectDir, manifest)
        return manifest
    }
}
