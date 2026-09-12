package dev.qutivex.engine.dependency

import java.nio.file.Files
import java.nio.file.Path

/**
 * Abstraction for local artifact caching and retrieval.
 */
interface ArtifactCache {
    fun getCacheDir(): Path
    fun contains(group: String, artifact: String, version: String): Boolean
}

class LocalArtifactCache(private val customDir: Path? = null) : ArtifactCache {
    override fun getCacheDir(): Path {
        if (customDir != null) return customDir
        val home = System.getProperty("user.home") ?: "."
        return Path.of(home, ".qutivex", "cache").toAbsolutePath().normalize()
    }

    override fun contains(group: String, artifact: String, version: String): Boolean {
        val groupPath = group.replace('.', '/')
        val jarName = "$artifact-$version.jar"
        val expectedJar = getCacheDir().resolve(groupPath).resolve(artifact).resolve(version).resolve(jarName)
        return Files.exists(expectedJar)
    }
}
