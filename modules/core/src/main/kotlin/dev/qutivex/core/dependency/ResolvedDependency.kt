package dev.qutivex.core.dependency

/**
 * A fully resolved dependency in the project graph, direct or transitive.
 */
data class ResolvedDependency(
    val group: String,
    val artifact: String,
    val version: String,
    val scope: String, // "runtime" or "test"
    val direct: Boolean,
    val dependencies: List<String> = emptyList(),
    val checksum: String? = null,
    val repository: String = "https://repo.maven.apache.org/maven2/",
) {
    val key: String get() = "$group:$artifact"
    val standardNotation: String get() = "$group:$artifact:$version"

    override fun toString(): String = "$standardNotation ($scope${if (direct) ", direct" else ", transitive"})"
}
