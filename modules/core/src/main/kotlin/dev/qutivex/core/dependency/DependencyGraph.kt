package dev.qutivex.core.dependency

/**
 * The resolved dependency graph of a project.
 */
data class DependencyGraph(
    val rootProjectName: String,
    val packages: List<ResolvedDependency>,
) {
    val directDependencies: List<ResolvedDependency> get() = packages.filter { it.direct }
    val transitiveDependencies: List<ResolvedDependency> get() = packages.filter { !it.direct }

    val runtimeDependencies: List<ResolvedDependency> get() = packages.filter { it.scope == "runtime" }
    val testDependencies: List<ResolvedDependency> get() = packages.filter { it.scope == "test" }

    fun find(group: String, artifact: String): ResolvedDependency? =
        packages.firstOrNull { it.group == group && it.artifact == artifact }
}
