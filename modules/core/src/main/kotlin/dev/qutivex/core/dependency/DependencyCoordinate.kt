package dev.qutivex.core.dependency

/**
 * Validated Maven dependency coordinate in either 'group:artifact:version' or 'group:artifact@version' format.
 */
data class DependencyCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
) {
    val key: String get() = "$group:$artifact"
    val standardNotation: String get() = "$group:$artifact:$version"
    val atNotation: String get() = "$group:$artifact@$version"

    override fun toString(): String = standardNotation

    companion object {
        private val PART_PATTERN = Regex("^[a-zA-Z0-9_.-]+$")
        private val VERSION_PATTERN = Regex("^[a-zA-Z0-9_.+-]+$")

        fun parse(input: String): DependencyCoordinate {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty()) { "Dependency coordinate must not be blank." }

            // Support both group:artifact:version and group:artifact@version
            val (keyPart, versionPart) = when {
                trimmed.contains('@') -> {
                    val parts = trimmed.split('@')
                    require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                        "Invalid '@' syntax in coordinate '$trimmed'. Expected 'group:artifact@version'."
                    }
                    parts[0] to parts[1]
                }
                else -> {
                    val parts = trimmed.split(':')
                    require(parts.size == 3) {
                        "Invalid coordinate '$trimmed'. Expected 'group:artifact:version' or 'group:artifact@version'."
                    }
                    "${parts[0]}:${parts[1]}" to parts[2]
                }
            }

            val keyParts = keyPart.split(':')
            require(keyParts.size == 2) {
                "Invalid group and artifact in coordinate '$trimmed'. Expected 'group:artifact'."
            }
            val group = keyParts[0]
            val artifact = keyParts[1]

            require(PART_PATTERN.matches(group)) {
                "Invalid characters in group ID '$group'. Only letters, digits, '.', '-', and '_' are allowed."
            }
            require(PART_PATTERN.matches(artifact)) {
                "Invalid characters in artifact ID '$artifact'. Only letters, digits, '.', '-', and '_' are allowed."
            }
            require(VERSION_PATTERN.matches(versionPart)) {
                "Invalid version '$versionPart' in coordinate '$trimmed'. Only letters, digits, '.', '-', '_', and '+' are allowed."
            }

            return DependencyCoordinate(group = group, artifact = artifact, version = versionPart)
        }

        fun parseKey(input: String): String {
            val trimmed = input.trim()
            val stripped = if (trimmed.contains('@')) trimmed.substringBefore('@') else trimmed
            val parts = stripped.split(':')
            require(parts.size in 2..3) {
                "Invalid coordinate key '$input'. Expected 'group:artifact'."
            }
            val group = parts[0]
            val artifact = parts[1]
            require(PART_PATTERN.matches(group)) { "Invalid characters in group ID '$group'." }
            require(PART_PATTERN.matches(artifact)) { "Invalid characters in artifact ID '$artifact'." }
            return "$group:$artifact"
        }
    }
}
