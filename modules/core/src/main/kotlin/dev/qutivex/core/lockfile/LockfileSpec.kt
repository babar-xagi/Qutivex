package dev.qutivex.core.lockfile

/**
 * Representation of the versioned qutivex.lock file (version = 1).
 */
data class LockfileSpec(
    val version: Int = 1,
    val manifestHash: String,
    val kotlinVersion: String = "2.4.10",
    val jvmTarget: Int = 21,
    val dependencies: Map<String, String> = emptyMap(),
    val testDependencies: Map<String, String> = emptyMap(),
) {
    init {
        require(version == 1) { "Unsupported lockfile version: $version" }
        require(manifestHash.isNotBlank()) { "Lockfile manifest-hash must not be blank." }
    }

    fun toToml(): String = buildString {
        append("# Qutivex lockfile (version = 1) - generated automatically, do not edit manually\n")
        append("version = $version\n")
        append("manifest-hash = \"$manifestHash\"\n\n")
        append("[toolchain]\n")
        append("kotlin = \"$kotlinVersion\"\n")
        append("jvm = $jvmTarget\n\n")
        append("[dependencies]\n")
        for ((coord, ver) in dependencies.toSortedMap()) {
            append("\"$coord\" = \"$ver\"\n")
        }
        append("\n[test-dependencies]\n")
        for ((coord, ver) in testDependencies.toSortedMap()) {
            append("\"$coord\" = \"$ver\"\n")
        }
        append("\n")
    }
}
