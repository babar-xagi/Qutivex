package dev.qutivex.engine.manifest

import dev.qutivex.core.manifest.ManifestSpec
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Safely writes a ManifestSpec to a qutivex.toml file atomically.
 */
class ManifestWriter {
    fun write(path: Path, manifest: ManifestSpec) {
        val normalized = path.toAbsolutePath().normalize()
        val parent = normalized.parent ?: Path.of(".")
        Files.createDirectories(parent)

        val tomlContent = manifest.toToml()
        val tempFile = Files.createTempFile(parent, "qutivex-manifest-", ".tmp")
        try {
            Files.writeString(tempFile, tomlContent, UTF_8)
            try {
                Files.move(tempFile, normalized, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: Exception) {
                // Fallback if atomic move across filesystems is not supported
                Files.move(tempFile, normalized, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
