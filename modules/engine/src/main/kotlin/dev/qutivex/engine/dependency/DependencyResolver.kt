package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.DependencyGraph
import dev.qutivex.core.manifest.ManifestSpec
import java.nio.file.Path

/**
 * Abstraction for dependency graph resolution.
 * Allows decoupling dependency resolution from the underlying execution backend.
 */
interface DependencyResolver {
    fun resolve(
        projectDir: Path,
        manifest: ManifestSpec,
        offline: Boolean = false,
        verbose: Boolean = false,
    ): DependencyGraph
}
