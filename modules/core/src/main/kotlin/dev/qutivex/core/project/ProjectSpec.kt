package dev.qutivex.core.project

import java.util.Locale

/** The project metadata written by `qutivex init`. */
data class ProjectSpec(
    val name: String,
    val kotlinVersion: String = "2.4.10",
    val jvmVersion: Int = 21,
    val version: String = "0.1.0",
    val mainClass: String = "MainKt",
) {
    init {
        require(NAME_PATTERN.matches(name)) {
            "Project name must start with a lowercase letter and contain only lowercase ASCII " +
                "letters, digits, or hyphens (1–64 characters): '$name'."
        }
        require(name !in WINDOWS_DEVICE_NAMES) {
            "Project name '$name' is reserved on Windows. Choose another name."
        }
    }

    companion object {
        private val NAME_PATTERN = Regex("[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?")
        private val SEPARATORS = Regex("[\\s_]+")
        private val REPEATED_HYPHENS = Regex("-+")
        private val WINDOWS_DEVICE_NAMES =
            setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

        /** Normalizes a directory name; [ProjectSpec] validates the result. */
        fun normalizeName(directoryName: String): String {
            val trimmed = directoryName.trim()
            require(trimmed.isNotEmpty()) {
                "Project directory name must not be blank."
            }
            val normalized = trimmed
                .lowercase(Locale.ROOT)
                .replace(SEPARATORS, "-")
                .replace(REPEATED_HYPHENS, "-")

            require(NAME_PATTERN.matches(normalized)) {
                "Project name must start with a lowercase letter and contain only lowercase ASCII " +
                    "letters, digits, or hyphens (1–64 characters): '$normalized'."
            }
            require(normalized !in WINDOWS_DEVICE_NAMES) {
                "Project name '$normalized' is reserved on Windows. Choose another name."
            }
            return normalized
        }
    }
}
