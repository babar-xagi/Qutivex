package dev.qutivex.engine.project

import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.backend.gradle.BackendProcessRunner
import dev.qutivex.engine.backend.gradle.GradleBackendGenerator
import dev.qutivex.engine.backend.gradle.GradleProcessRunner
import dev.qutivex.engine.manifest.ManifestParser
import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path

/** Orchestrates manifest parsing, disposable backend generation, and project execution. */
class ProjectExecutor(
    private val manifestParser: ManifestParser = ManifestParser(),
    private val backendGenerator: GradleBackendGenerator = GradleBackendGenerator(),
    private val processRunner: BackendProcessRunner = GradleProcessRunner(),
) {
    fun run(
        projectDir: Path,
        args: List<String> = emptyList(),
        stdout: PrintWriter,
        stderr: PrintWriter,
        stdin: InputStream? = null,
    ): Int {
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
        val extraArgs = listOf("--quiet")
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
    ): Int {
        prepare(projectDir)
        return processRunner.execute(
            projectDir = projectDir,
            tasks = listOf("test"),
            extraArgs = listOf("--console=plain"),
            stdout = stdout,
            stderr = stderr,
        )
    }

    fun build(
        projectDir: Path,
        stdout: PrintWriter,
        stderr: PrintWriter,
    ): Int {
        prepare(projectDir)
        return processRunner.execute(
            projectDir = projectDir,
            tasks = listOf("build"),
            extraArgs = listOf("--console=plain"),
            stdout = stdout,
            stderr = stderr,
        )
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
