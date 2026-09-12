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
 * Uses DependencyResolver for dependency graph discovery.
 */
class DependencyManager(
    private val manifestParser: ManifestParser = ManifestParser(),
    private val manifestWriter: ManifestWriter = ManifestWriter(),
    private val lockfileManager: LockfileManager = LockfileManager(),
    private val backendGenerator: GradleBackendGenerator = GradleBackendGenerator(),
    private val processRunner: BackendProcessRunner = GradleProcessRunner(),
    private val dependencyResolver: DependencyResolver = GradleDependencyResolver(backendGenerator, processRunner),
) {
    fun add(
        projectDir: Path,
        coordinate: DependencyCoordinate,
        isTest: Boolean = false,
        verbose: Boolean = false,
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

        // 2. Resolve dependency graph via resolver (validates without compiling)
        val graph = try {
            dependencyResolver.resolve(
                projectDir = projectDir,
                manifest = updatedManifest,
                offline = false,
                verbose = verbose,
            )
        } catch (e: Exception) {
            // Roll back to previous manifest on failure
            manifestWriter.write(manifestFile, currentManifest)
            backendGenerator.generate(projectDir, currentManifest)
            throw DependencyException(
                "Failed to resolve dependency '${coordinate.standardNotation}'. Rolled back changes.\n${e.message ?: ""}".trim()
            )
        }

        // 3. Update lockfile with full resolved graph
        lockfileManager.write(projectDir, updatedManifest, graph)

        return updatedManifest
    }

    fun remove(
        projectDir: Path,
        coordinateKey: String,
        isTest: Boolean = false,
        verbose: Boolean = false,
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
                currentManifest.withoutTestDependency(normalizedKey)
            } else {
                currentManifest.withoutDependency(normalizedKey)
            }
        }

        manifestWriter.write(manifestFile, updatedManifest)
        backendGenerator.generate(projectDir, updatedManifest)

        // Resolve remaining dependencies and update lockfile
        val graph = try {
            dependencyResolver.resolve(projectDir, updatedManifest, offline = false, verbose = verbose)
        } catch (_: Exception) {
            null
        }
        lockfileManager.write(projectDir, updatedManifest, graph)

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
        verbose: Boolean = false,
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

        val graph = if (!frozen) {
            val resolved = dependencyResolver.resolve(projectDir, manifest, offline = offline, verbose = verbose)
            lockfileManager.write(projectDir, manifest, resolved)
            resolved
        } else {
            null
        }

        backendGenerator.generate(projectDir, manifest)

        val extraArgs = mutableListOf("--console=plain", "--build-cache")
        if (offline) {
            extraArgs.add("--offline")
        }

        val outWriter = if (verbose) stdout else PrintWriter(StringWriter())
        val errCapture = StringWriter()
        val errWriter = if (verbose) stderr else PrintWriter(errCapture)

        val exitCode = processRunner.execute(
            projectDir = projectDir,
            tasks = listOf("classes", "testClasses"),
            extraArgs = extraArgs,
            stdout = outWriter,
            stderr = errWriter,
        )

        if (exitCode != 0 && !verbose) {
            val errText = errCapture.toString().trim()
            if (errText.isNotBlank()) {
                stderr.println(errText)
            }
        }

        return exitCode
    }
}
