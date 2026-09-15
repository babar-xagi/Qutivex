package dev.qutivex.core.environment

data class EnvironmentSpec(
    val projectName: String,
    val projectVersion: String,
    val kotlinVersion: String,
    val jvmVersion: Int,
    val kotlinPath: String = "",
    val jdkPath: String = "",
    val compilerOptions: List<String> = emptyList(),
    val classpathEntries: List<String> = emptyList(),
    val directDependencyCount: Int = 0,
    val transitiveDependencyCount: Int = 0,
    val status: String = "INITIALIZED",
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    fun toToml(): String = buildString {
        append("[environment]\n")
        append("project = \"$projectName\"\n")
        append("version = \"$projectVersion\"\n")
        append("kotlin = \"$kotlinVersion\"\n")
        append("jvm = $jvmVersion\n")
        if (kotlinPath.isNotBlank()) append("kotlin-path = \"${kotlinPath.replace("\\", "/")}\"\n")
        if (jdkPath.isNotBlank()) append("jdk-path = \"${jdkPath.replace("\\", "/")}\"\n")
        append("status = \"$status\"\n")
        if (createdAt.isNotBlank()) append("created-at = \"$createdAt\"\n")
        if (updatedAt.isNotBlank()) append("updated-at = \"$updatedAt\"\n")
        append("\n[compiler-options]\n")
        for (opt in compilerOptions) {
            append("options = [\"$opt\"]\n")
        }
    }
}

data class ProjectStateSpec(
    val projectName: String,
    val version: String,
    val status: String,
    val lockHash: String = "",
    val manifestHash: String = "",
    val directDependencies: Int = 0,
    val transitiveDependencies: Int = 0,
    val lastBuildTime: Long = 0L,
)
