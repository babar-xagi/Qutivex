package dev.qutivex.core.lockfile

import dev.qutivex.core.dependency.ResolvedDependency

/**
 * Representation of the versioned qutivex.lock file (version = 1).
 * Records the complete resolved dependency graph including transitive packages.
 */
data class LockfileSpec(
    val version: Int = 1,
    val manifestHash: String,
    val kotlinVersion: String = "2.4.10",
    val jvmTarget: Int = 21,
    val backendType: String = "gradle",
    val backendVersion: String = "9.5.0",
    val packages: List<ResolvedDependency> = emptyList(),
) {
    init {
        require(version == 1) { "Unsupported lockfile version: $version" }
        require(manifestHash.isNotBlank()) { "Lockfile manifest-hash must not be blank." }
    }

    val directDependencies: List<ResolvedDependency> get() = packages.filter { it.direct }
    val transitiveDependencies: List<ResolvedDependency> get() = packages.filter { !it.direct }

    val dependencies: Map<String, String>
        get() = packages.filter { it.direct && it.scope == "runtime" }.associate { it.key to it.version }

    val testDependencies: Map<String, String>
        get() = packages.filter { it.direct && it.scope == "test" }.associate { it.key to it.version }

    fun toToml(): String = buildString {
        append("# Qutivex lockfile (version = 1) - generated automatically, do not edit manually\n")
        append("version = $version\n")
        append("manifest-hash = \"$manifestHash\"\n\n")
        append("[toolchain]\n")
        append("kotlin = \"$kotlinVersion\"\n")
        append("jvm = $jvmTarget\n\n")
        append("[backend]\n")
        append("type = \"$backendType\"\n")
        append("gradle = \"$backendVersion\"\n")

        val sortedPackages = packages.sortedWith(
            compareBy<ResolvedDependency> { it.scope }
                .thenBy { it.group }
                .thenBy { it.artifact }
                .thenBy { it.version }
        )

        for (pkg in sortedPackages) {
            append("\n[[package]]\n")
            append("group = \"${escapeToml(pkg.group)}\"\n")
            append("artifact = \"${escapeToml(pkg.artifact)}\"\n")
            append("version = \"${escapeToml(pkg.version)}\"\n")
            append("scope = \"${escapeToml(pkg.scope)}\"\n")
            append("direct = ${pkg.direct}\n")
            if (pkg.dependencies.isNotEmpty()) {
                val sortedDeps = pkg.dependencies.sorted()
                append("dependencies = [${sortedDeps.joinToString(", ") { "\"${escapeToml(it)}\"" }}]\n")
            }
            if (!pkg.checksum.isNullOrBlank()) {
                append("checksum = \"${escapeToml(pkg.checksum)}\"\n")
            }
            if (pkg.repository.isNotBlank()) {
                append("repository = \"${escapeToml(pkg.repository)}\"\n")
            }
        }
    }

    companion object {
        private fun escapeToml(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
