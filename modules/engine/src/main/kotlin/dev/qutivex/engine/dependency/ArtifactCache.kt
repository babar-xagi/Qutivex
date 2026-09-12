package dev.qutivex.engine.dependency

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class ArtifactIntegrityException(
    val group: String,
    val artifact: String,
    val version: String,
    val expectedSha256: String,
    val actualSha256: String,
    message: String = buildString {
        append("Artifact integrity verification failed.\n\n")
        append("Artifact:\n$group:$artifact:$version\n\n")
        append("Expected SHA-256:\n$expectedSha256\n\n")
        append("Actual SHA-256:\n$actualSha256\n\n")
        append("The cached artifact may be corrupted or modified.")
    }
) : DependencyException(message)

class MissingOfflineArtifactException(
    val group: String,
    val artifact: String,
    val version: String,
    val expectedLocation: Path,
    message: String = buildString {
        append("Offline installation cannot continue.\n\n")
        append("Missing artifact:\n$group:$artifact:$version\n\n")
        append("Expected cache location:\n$expectedLocation\n\n")
        append("Run without --offline when network access is available.")
    }
) : DependencyException(message)

/**
 * Abstraction for local artifact caching and integrity verification.
 */
interface ArtifactCache {
    fun getCacheDir(): Path
    fun contains(group: String, artifact: String, version: String): Boolean
    fun findArtifact(group: String, artifact: String, version: String): Path?
    fun findPom(group: String, artifact: String, version: String): Path?
    fun getExpectedLocation(group: String, artifact: String, version: String): Path
    fun verifyIntegrity(group: String, artifact: String, version: String, expectedChecksum: String?): Path?
}

class LocalArtifactCache(
    private val customDir: Path? = null,
    private val customGradleCacheDir: Path? = null,
) : ArtifactCache {

    override fun getCacheDir(): Path {
        if (customDir != null) return customDir
        val home = System.getProperty("user.home") ?: "."
        return Path.of(home, ".qutivex", "cache").toAbsolutePath().normalize()
    }

    private fun getGradleCacheDir(): Path {
        if (customGradleCacheDir != null) return customGradleCacheDir
        val home = System.getProperty("user.home") ?: "."
        return Path.of(home, ".gradle", "caches", "modules-2", "files-2.1").toAbsolutePath().normalize()
    }

    override fun contains(group: String, artifact: String, version: String): Boolean {
        return findArtifact(group, artifact, version) != null || findPom(group, artifact, version) != null
    }

    override fun findArtifact(group: String, artifact: String, version: String): Path? {
        val jarName = "$artifact-$version.jar"

        // 1. Check customDir if provided (e.g. for testing)
        if (customDir != null && Files.exists(customDir)) {
            val direct = customDir.resolve(jarName)
            if (Files.exists(direct)) return direct

            val nested = customDir.resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(jarName)
            if (Files.exists(nested)) return nested

            try {
                val found = Files.walk(customDir, 5)
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == jarName }
                    .findFirst()
                if (found.isPresent) return found.get()
            } catch (_: Exception) {}
        }

        // 2. Check Gradle files-2.1 cache
        val gradleDir = getGradleCacheDir().resolve(group).resolve(artifact).resolve(version)
        if (Files.exists(gradleDir) && Files.isDirectory(gradleDir)) {
            try {
                val found = Files.walk(gradleDir, 3)
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == jarName }
                    .findFirst()
                if (found.isPresent) return found.get()
            } catch (_: Exception) {}
        }

        // 3. Check Qutivex cache
        val qutivexJar = getCacheDir().resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(jarName)
        if (Files.exists(qutivexJar)) return qutivexJar

        return null
    }

    override fun findPom(group: String, artifact: String, version: String): Path? {
        val pomName = "$artifact-$version.pom"

        if (customDir != null && Files.exists(customDir)) {
            val direct = customDir.resolve(pomName)
            if (Files.exists(direct)) return direct

            val nested = customDir.resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(pomName)
            if (Files.exists(nested)) return nested

            try {
                val found = Files.walk(customDir, 5)
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == pomName }
                    .findFirst()
                if (found.isPresent) return found.get()
            } catch (_: Exception) {}
        }

        val gradleDir = getGradleCacheDir().resolve(group).resolve(artifact).resolve(version)
        if (Files.exists(gradleDir) && Files.isDirectory(gradleDir)) {
            try {
                val found = Files.walk(gradleDir, 3)
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == pomName }
                    .findFirst()
                if (found.isPresent) return found.get()
            } catch (_: Exception) {}
        }

        val qutivexPom = getCacheDir().resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(pomName)
        if (Files.exists(qutivexPom)) return qutivexPom

        return null
    }

    override fun getExpectedLocation(group: String, artifact: String, version: String): Path {
        if (customDir != null) {
            return customDir.resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve("$artifact-$version.jar")
        }
        return getGradleCacheDir().resolve(group).resolve(artifact).resolve(version).resolve("$artifact-$version.jar")
    }

    override fun verifyIntegrity(
        group: String,
        artifact: String,
        version: String,
        expectedChecksum: String?,
    ): Path? {
        val file = findArtifact(group, artifact, version) ?: findPom(group, artifact, version)
        if (file == null) return null

        if (expectedChecksum.isNullOrBlank()) {
            return file
        }

        val expectedClean = expectedChecksum.removePrefix("sha256:").trim()
        val actualHash = computeSha256(file)

        if (!expectedClean.equals(actualHash, ignoreCase = true)) {
            throw ArtifactIntegrityException(
                group = group,
                artifact = artifact,
                version = version,
                expectedSha256 = expectedClean,
                actualSha256 = actualHash,
            )
        }

        return file
    }

    companion object {
        fun computeSha256(path: Path): String {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
