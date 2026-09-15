package dev.qutivex.core.toolchain

import java.nio.file.Path

enum class ToolchainType(val identifier: String) {
    KOTLIN("kotlin"),
    JDK("jdk");

    companion object {
        fun from(value: String): ToolchainType {
            return entries.firstOrNull { it.identifier.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown toolchain type '$value'. Expected 'kotlin' or 'jdk'.")
        }
    }
}

data class ToolchainInfo(
    val type: ToolchainType,
    val version: String,
    val path: Path,
    val isManaged: Boolean = true,
    val isActive: Boolean = false,
    val isSystem: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(version.isNotBlank()) { "Toolchain version must not be blank." }
    }
}

data class ToolchainResolution(
    val kotlin: ToolchainInfo,
    val jdk: ToolchainInfo,
)
