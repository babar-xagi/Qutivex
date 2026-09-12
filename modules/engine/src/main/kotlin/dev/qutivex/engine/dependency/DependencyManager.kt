package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.DependencyCoordinate
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.backend.gradle.BackendProcessRunner
import dev.qutivex.engine.backend.gradle.GradleBackendGenerator
import dev.qutivex.engine.backend.gradle.GradleProcessRunner
import dev.qutivex.engine.lockfile.LockfileManager
import dev.qutivex.engine.manifest.ManifestParser
import dev.qutivex.engine.manifest.ManifestWriter
import dev.qutivex.engine.project.ProjectLockManager
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

open class DependencyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Handles adding, removing, listing, and installing project dependencies.
 * Uses DependencyResolver for dependency graph discovery, ArtifactCache for integrity verification,
 * and ProjectLockManager for mutual exclusion during project mutations.
 */
class DependencyManager(
    private val manifestParser: ManifestParser = ManifestParser(),
    private val manifestWriter: ManifestWriter = ManifestWriter(),
    private val lockfileManager: LockfileManager = LockfileManager(),
    private val backendGenerator: GradleBackendGenerator = GradleBackendGenerator(),
    private val processRunner: BackendProcessRunner = GradleProcessRunner(),
    private val dependencyResolver: DependencyResolver = GradleDependencyResolver(backendGenerator, processRunner),
    private val artifactCache: ArtifactCache = LocalArtifactCache(),
) {
    fun add(
        projectDir: Path,
        coordinate: DependencyCoordinate,
        isTest: Boolean = false,
        verbose: Boolean = false,
        stdout: PrintWriter,
        stderr: PrintWriter,
    ): ManifestSpec = ProjectLockManager.acquire(projectDir, "add").use {
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
    ): ManifestSpec = ProjectLockManager.acquire(projectDir, "remove").use {
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
    ): Int = ProjectLockManager.acquire(projectDir, "install").use {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw DependencyException("No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.")
        }
        val manifest = manifestParser.parse(manifestFile)

        if (frozen) {
            lockfileManager.verifyFrozen(projectDir, manifest)
            val lockfile = lockfileManager.read(projectDir)
                ?: throw DependencyException("Failed to read 'qutivex.lock'.")

            // Verify integrity and presence of all locked packages
            for (pkg in lockfile.packages) {
                val file = artifactCache.findArtifact(pkg.group, pkg.artifact, pkg.version)
                    ?: artifactCache.findPom(pkg.group, pkg.artifact, pkg.version)

                if (file == null) {
                    if (offline) {
                        throw MissingOfflineArtifactException(
                            group = pkg.group,
                            artifact = pkg.artifact,
                            version = pkg.version,
                            expectedLocation = artifactCache.getExpectedLocation(pkg.group, pkg.artifact, pkg.version),
                        )
                    }
                } else {
                    // Cached file exists - verify integrity against trusted lockfile checksum
                    artifactCache.verifyIntegrity(pkg.group, pkg.artifact, pkg.version, pkg.checksum)
                }
            }
        } else {
            // Online or unfrozen install
            val existingLock = lockfileManager.read(projectDir)
            if (existingLock != null) {
                for (pkg in existingLock.packages) {
                    if (artifactCache.contains(pkg.group, pkg.artifact, pkg.version)) {
                        artifactCache.verifyIntegrity(pkg.group, pkg.artifact, pkg.version, pkg.checksum)
                    }
                }
            }

            val resolved = dependencyResolver.resolve(projectDir, manifest, offline = offline, verbose = verbose)
            lockfileManager.write(projectDir, manifest, resolved)
        }

        backendGenerator.generate(projectDir, manifest)

        val extraArgs = mutableListOf("--console=plain", "--build-cache")
        if (offline) {
            extraArgs.add("--offline")
        }

        val outCapture = StringWriter()
        val outWriter = if (verbose) stdout else PrintWriter(outCapture)
        val errCapture = StringWriter()
        val errWriter = if (verbose) stderr else PrintWriter(errCapture)

        val exitCode = processRunner.execute(
            projectDir = projectDir,
            tasks = listOf("classes", "testClasses"),
            extraArgs = extraArgs,
            stdout = outWriter,
            stderr = errWriter,
        )

        if (exitCode != 0) {
            val errText = errCapture.toString().trim()
            val combinedText = "$errText\n${outCapture.toString().trim()}"
            if (offline) {
                val missingMatch = findMissingOfflineCoordinate(combinedText)
                if (missingMatch != null) {
                    val (g, a, v) = missingMatch
                    throw MissingOfflineArtifactException(
                        group = g,
                        artifact = a,
                        version = v,
                        expectedLocation = artifactCache.getExpectedLocation(g, a, v),
                    )
                }
            }
            if (!verbose && errText.isNotBlank()) {
                stderr.println(errText)
            }
        }

        return exitCode
    }

    companion object {
        private val OFFLINE_MISSING_PATTERNS = listOf(
            Regex("""Could not find ([^:\s]+):([^:\s]+):([^:\s]+)"""),
            Regex("""Could not resolve ([^:\s]+):([^:\s]+):([^:\s]+)"""),
            Regex("""Cannot resolve external dependency ([^:\s]+):([^:\s]+):([^:\s]+)"""),
            Regex("""No cached version of ([^:\s]+):([^:\s]+):([^:\s]+) available"""),
        )

        fun findMissingOfflineCoordinate(text: String): Triple<String, String, String>? {
            for (pattern in OFFLINE_MISSING_PATTERNS) {
                val match = pattern.find(text)
                if (match != null) {
                    val g = match.groupValues[1].trimEnd('.')
                    val a = match.groupValues[2].trimEnd('.')
                    val v = match.groupValues[3].trimEnd('.')
                    return Triple(g, a, v)
                }
            }
            return null
        }
    }
}
