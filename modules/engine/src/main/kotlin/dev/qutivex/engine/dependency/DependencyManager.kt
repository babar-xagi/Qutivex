package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.DependencyCoordinate
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.backend.gradle.BackendProcessRunner
import dev.qutivex.engine.backend.gradle.GradleBackendGenerator
import dev.qutivex.engine.backend.gradle.GradleProcessRunner
import dev.qutivex.engine.lockfile.LockfileManager
import dev.qutivex.engine.manifest.ManifestParser
import dev.qutivex.engine.manifest.ManifestWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

class DependencyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Handles adding, removing, listing, and installing project dependencies.
 */
class DependencyManager(
    private val manifestParser: ManifestParser = ManifestParser(),
    private val manifestWriter: ManifestWriter = ManifestWriter(),
    private val lockfileManager: LockfileManager = LockfileManager(),
    private val backendGenerator: GradleBackendGenerator = GradleBackendGenerator(),
    private val processRunner: BackendProcessRunner = GradleProcessRunner(),
) {
    fun add(
        projectDir: Path,
        coordinate: DependencyCoordinate,
        isTest: Boolean = false,
        stdout: PrintWriter,
        stderr: PrintWriter,
    ): ManifestSpec {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw DependencyException("No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.")
        }
        val currentManifest = manifestParser.parse(manifestFile)

        val targetMap = if (isTest) currentManifest.testDependencies else currentManifest.dependencies
        if (targetMap[coordinate.key] == coordinate.version) {
            stdout.println("Dependency '${coordinate.standardNotation}' is already present.")
            return currentManifest
        }

        val updatedManifest = if (isTest) {
            currentManifest.withTestDependency(coordinate.key, coordinate.version)
        } else {
            currentManifest.withDependency(coordinate.key, coordinate.version)
        }

        // 1. Stage updated manifest to disk
        manifestWriter.write(manifestFile, updatedManifest)

        // 2. Generate backend
        backendGenerator.generate(projectDir, updatedManifest)

        // 3. Verify dependency resolution via backend
        val resolutionOutput = StringWriter()
        val resolutionError = StringWriter()
        val verifyTask = if (isTest) listOf("compileTestKotlin") else listOf("compileKotlin")
        val exitCode = processRunner.execute(
            projectDir = projectDir,
            tasks = verifyTask,
            extraArgs = listOf("--quiet", "--build-cache"),
            stdout = PrintWriter(resolutionOutput),
            stderr = PrintWriter(resolutionError),
        )

        if (exitCode != 0) {
            // Roll back to previous manifest on failure
            manifestWriter.write(manifestFile, currentManifest)
            backendGenerator.generate(projectDir, currentManifest)
            val errMessage = resolutionError.toString().trim().ifEmpty { resolutionOutput.toString().trim() }
            throw DependencyException(
                "Failed to resolve dependency '${coordinate.standardNotation}'. Rolled back changes.\n" +
                errMessage.lineSequence().filter { it.isNotBlank() }.take(5).joinToString("\n")
            )
        }

        // 4. Update lockfile after successful resolution
        lockfileManager.write(projectDir, updatedManifest)

        return updatedManifest
    }

    fun remove(
        projectDir: Path,
        coordinateKey: String,
        isTest: Boolean = false,
    ): ManifestSpec {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw DependencyException("No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.")
        }
        val currentManifest = manifestParser.parse(manifestFile)

        val normalizedKey = DependencyCoordinate.parseKey(coordinateKey)
        val inRuntime = currentManifest.dependencies.containsKey(normalizedKey)
        val inTest = currentManifest.testDependencies.containsKey(normalizedKey)

        if (!inRuntime && !inTest) {
            throw DependencyException("Dependency '$normalizedKey' was not found in 'qutivex.toml'.")
        }

        val updatedManifest = if (isTest) {
            if (!inTest) {
                throw DependencyException("Dependency '$normalizedKey' was not found in [test-dependencies].")
            }
            currentManifest.withoutTestDependency(normalizedKey)
        } else {
            if (!inRuntime && inTest) {
                // If user didn't specify --test but it's in test-dependencies, remove from test
                currentManifest.withoutTestDependency(normalizedKey)
            } else {
                currentManifest.withoutDependency(normalizedKey)
            }
        }

        manifestWriter.write(manifestFile, updatedManifest)
        backendGenerator.generate(projectDir, updatedManifest)
        lockfileManager.write(projectDir, updatedManifest)

        return updatedManifest
    }

    fun list(projectDir: Path): ManifestSpec {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw DependencyException("No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.")
        }
        return manifestParser.parse(manifestFile)
    }

    fun install(
        projectDir: Path,
        frozen: Boolean = false,
        offline: Boolean = false,
        stdout: PrintWriter,
        stderr: PrintWriter,
    ): Int {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw DependencyException("No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.")
        }
        val manifest = manifestParser.parse(manifestFile)

        if (frozen) {
            lockfileManager.verifyFrozen(projectDir, manifest)
        }

        backendGenerator.generate(projectDir, manifest)

        val extraArgs = mutableListOf("--console=plain", "--build-cache")
        if (offline) {
            extraArgs.add("--offline")
        }

        val exitCode = processRunner.execute(
            projectDir = projectDir,
            tasks = listOf("classes", "testClasses"),
            extraArgs = extraArgs,
            stdout = stdout,
            stderr = stderr,
        )

        if (exitCode == 0 && !frozen) {
            lockfileManager.write(projectDir, manifest)
        }

        return exitCode
    }
}
