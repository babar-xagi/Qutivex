package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.DependencyGraph
import dev.qutivex.core.dependency.ResolvedDependency
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.backend.gradle.BackendProcessRunner
import dev.qutivex.engine.backend.gradle.GradleBackendGenerator
import dev.qutivex.engine.backend.gradle.GradleProcessRunner
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves dependencies using a lightweight Gradle resolution task (qutivexResolve).
 * This inspects incoming configuration resolution graphs without executing Kotlin source compilation.
 */
class GradleDependencyResolver(
    private val backendGenerator: GradleBackendGenerator = GradleBackendGenerator(),
    private val processRunner: BackendProcessRunner = GradleProcessRunner(),
) : DependencyResolver {

    override fun resolve(
        projectDir: Path,
        manifest: ManifestSpec,
        offline: Boolean,
        verbose: Boolean,
    ): DependencyGraph {
        // 1. Generate disposable backend with resolution task
        val gradleDir = backendGenerator.generate(projectDir, manifest)

        // 2. Run lightweight resolution task
        val extraArgs = mutableListOf("--quiet", "--build-cache")
        if (offline) {
            extraArgs.add("--offline")
        }

        val outCapture = StringWriter()
        val errCapture = StringWriter()
        val exitCode = processRunner.execute(
            projectDir = projectDir,
            tasks = listOf("qutivexResolve"),
            extraArgs = extraArgs,
            stdout = PrintWriter(outCapture),
            stderr = PrintWriter(errCapture),
        )

        if (exitCode != 0) {
            val errText = errCapture.toString().trim().ifEmpty { outCapture.toString().trim() }
            if (offline) {
                val missing = DependencyManager.findMissingOfflineCoordinate(errText)
                if (missing != null) {
                    val (g, a, v) = missing
                    val cache = LocalArtifactCache()
                    throw MissingOfflineArtifactException(g, a, v, cache.getExpectedLocation(g, a, v))
                }
            }
            val diagnostic = errText.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("BUILD FAILED") && !it.startsWith("FAILURE:") }
                .take(6)
                .joinToString("\n")
                .ifEmpty { "Dependency resolution failed with exit code $exitCode." }
            throw DependencyException("Failed to resolve project dependencies.\n$diagnostic")
        }

        // 3. Parse generated resolved-dependencies.txt
        val resolvedFile = gradleDir.resolve("resolved-dependencies.txt")
        if (!Files.exists(resolvedFile)) {
            throw DependencyException("Resolution completed but no dependency artifact details were emitted.")
        }

        val packages = mutableListOf<ResolvedDependency>()
        val seen = mutableSetOf<String>()

        Files.readAllLines(resolvedFile, StandardCharsets.UTF_8).forEach { line ->
            if (line.isNotBlank()) {
                val parts = line.split('\t')
                if (parts.size >= 7) {
                    val group = parts[0]
                    val artifact = parts[1]
                    val version = parts[2]
                    val scope = parts[3]
                    val direct = parts[4].toBoolean()
                    val checksum = parts[5].ifBlank { null }
                    val repo = parts[6].ifBlank { "https://repo.maven.apache.org/maven2/" }
                    val childDeps = if (parts.size > 7 && parts[7].isNotBlank()) {
                        parts[7].split(',').filter { it.isNotBlank() }
                    } else emptyList()

                    val dedupeKey = "$scope:$group:$artifact"
                    if (seen.add(dedupeKey)) {
                        packages.add(
                            ResolvedDependency(
                                group = group,
                                artifact = artifact,
                                version = version,
                                scope = scope,
                                direct = direct,
                                dependencies = childDeps,
                                checksum = checksum,
                                repository = repo,
                            )
                        )
                    }
                }
            }
        }

        return DependencyGraph(
            rootProjectName = manifest.project.name,
            packages = packages,
        )
    }
}
