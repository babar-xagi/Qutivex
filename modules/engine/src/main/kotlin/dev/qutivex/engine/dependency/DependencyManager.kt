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
import java.nio.file.Files
import java.nio.file.Path

open class DependencyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class TransitiveChange(
    val key: String,
    val scope: String,
    val oldVersion: String?,
    val newVersion: String?,
    val type: ChangeType,
) {
    enum class ChangeType { UPGRADED, DOWNGRADED, ADDED, REMOVED }
}

data class UpdateResult(
    val coordinate: DependencyCoordinate,
    val oldVersion: String,
    val newVersion: String,
    val isTest: Boolean,
    val transitiveChanges: List<TransitiveChange>,
)

/**
 * Handles adding, removing, listing, and installing project dependencies.
 * Uses NativeDependencyResolver for completely Gradle-free resolution,
 * ArtifactCache for local caching and integrity verification,
 * and ProjectLockManager for mutual exclusion during project mutations.
 */
class DependencyManager(
    private val manifestParser: ManifestParser = ManifestParser(),
    private val manifestWriter: ManifestWriter = ManifestWriter(),
    private val lockfileManager: LockfileManager = LockfileManager(),
    private val backendGenerator: GradleBackendGenerator = GradleBackendGenerator(),
    private val processRunner: BackendProcessRunner = GradleProcessRunner(),
    dependencyResolver: DependencyResolver? = null,
    private val artifactCache: ArtifactCache = LocalArtifactCache(),
) {
    private val dependencyResolver: DependencyResolver =
        dependencyResolver ?: NativeDependencyResolver(artifactCache = artifactCache)

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

        // 2. Resolve dependency graph natively (Gradle-free)
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

        // 4. Update disposable backend files for future run/test/build
        backendGenerator.generate(projectDir, updatedManifest)

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

        // Resolve remaining dependencies natively and update lockfile
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
                    } else {
                        // Online frozen install: resolve missing artifact natively without mutating lockfile
                        dependencyResolver.resolve(projectDir, manifest, offline = false, verbose = verbose)
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

        // Update disposable backend files for future run/test/build (zero Gradle process execution)
        backendGenerator.generate(projectDir, manifest)

        return 0
    }

    fun update(
        projectDir: Path,
        coordinate: DependencyCoordinate,
        isTest: Boolean? = null,
        verbose: Boolean = false,
        stdout: PrintWriter = PrintWriter(System.out),
        stderr: PrintWriter = PrintWriter(System.err),
    ): UpdateResult = ProjectLockManager.acquire(projectDir, "update").use {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw DependencyException("No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.")
        }
        val currentManifest = manifestParser.parse(manifestFile)

        val inRuntime = currentManifest.dependencies.containsKey(coordinate.key)
        val inTest = currentManifest.testDependencies.containsKey(coordinate.key)

        val actualIsTest = when {
            isTest != null -> isTest
            inRuntime && inTest -> false
            inRuntime -> false
            inTest -> true
            else -> throw DependencyException(
                "Dependency '${coordinate.key}' is not declared in qutivex.toml. Use 'qutivex add' to add a new dependency."
            )
        }

        val oldVersion = if (actualIsTest) {
            currentManifest.testDependencies[coordinate.key]
        } else {
            currentManifest.dependencies[coordinate.key]
        } ?: throw DependencyException(
            "Dependency '${coordinate.key}' is not declared in ${if (actualIsTest) "[test-dependencies]" else "[dependencies]"}. Use 'qutivex add' to add a new dependency."
        )

        if (oldVersion == coordinate.version) {
            return UpdateResult(coordinate, oldVersion, coordinate.version, actualIsTest, emptyList())
        }

        val oldLock = lockfileManager.read(projectDir)
        val oldPackages: Map<String, dev.qutivex.core.dependency.ResolvedDependency> =
            oldLock?.packages?.associateBy { "${it.group}:${it.artifact}:${it.scope}" } ?: emptyMap()

        val updatedManifest = if (actualIsTest) {
            currentManifest.withTestDependency(coordinate.key, coordinate.version)
        } else {
            currentManifest.withDependency(coordinate.key, coordinate.version)
        }

        // 1. Stage updated manifest to disk
        manifestWriter.write(manifestFile, updatedManifest)

        // 2. Resolve dependency graph natively
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
                "Failed to resolve update for '${coordinate.key}' to version '${coordinate.version}'. Rolled back changes.\n${e.message ?: ""}".trim()
            )
        }

        // 3. Write updated lockfile
        lockfileManager.write(projectDir, updatedManifest, graph)

        // 4. Update backend generator files
        backendGenerator.generate(projectDir, updatedManifest)

        // 5. Compute transitive changes
        val newPackages = graph.packages.associateBy { "${it.group}:${it.artifact}:${it.scope}" }
        val transitiveChanges = mutableListOf<TransitiveChange>()

        for ((keyWithScope, newPkg) in newPackages) {
            val key = "${newPkg.group}:${newPkg.artifact}"
            if (key == coordinate.key) continue
            val oldPkg = oldPackages[keyWithScope]
            if (oldPkg == null) {
                transitiveChanges.add(TransitiveChange(key, newPkg.scope, null, newPkg.version, TransitiveChange.ChangeType.ADDED))
            } else if (oldPkg.version != newPkg.version) {
                val type = if (newPkg.version > oldPkg.version) TransitiveChange.ChangeType.UPGRADED else TransitiveChange.ChangeType.DOWNGRADED
                transitiveChanges.add(TransitiveChange(key, newPkg.scope, oldPkg.version, newPkg.version, type))
            }
        }

        for ((keyWithScope, oldPkg) in oldPackages) {
            val key = "${oldPkg.group}:${oldPkg.artifact}"
            if (key == coordinate.key) continue
            if (!newPackages.containsKey(keyWithScope)) {
                transitiveChanges.add(TransitiveChange(key, oldPkg.scope, oldPkg.version, null, TransitiveChange.ChangeType.REMOVED))
            }
        }

        return UpdateResult(
            coordinate = coordinate,
            oldVersion = oldVersion,
            newVersion = coordinate.version,
            isTest = actualIsTest,
            transitiveChanges = transitiveChanges.sortedWith(compareBy({ it.scope }, { it.key })),
        )
    }

    fun tree(
        projectDir: Path,
        scope: String = "all",
        maxDepth: Int = Int.MAX_VALUE,
        verbose: Boolean = false,
    ): String {
        val lockfile = lockfileManager.read(projectDir)
            ?: throw DependencyException("No 'qutivex.lock' found in '$projectDir'. Run 'qutivex install' first.")
        val manifestFile = projectDir.resolve("qutivex.toml")
        val manifest = if (Files.exists(manifestFile)) manifestParser.parse(manifestFile) else null
        val projectName = manifest?.project?.name ?: "project"
        val projectVersion = manifest?.project?.version ?: "0.1.0"

        return DependencyTreeRenderer.render(
            projectName = projectName,
            projectVersion = projectVersion,
            projectPath = projectDir.toAbsolutePath().normalize().toString(),
            packages = lockfile.packages,
            scope = scope,
            maxDepth = maxDepth,
            verbose = verbose,
        )
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
