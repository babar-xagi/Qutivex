package dev.qutivex.engine.dependency

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
    },
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
    },
) : DependencyException(message)

/**
 * Abstraction for local artifact caching and integrity verification.
 */
interface ArtifactCache {
    fun getCacheDir(): Path
    fun contains(group: String, artifact: String, version: String): Boolean
    fun findArtifact(
        group: String,
        artifact: String,
        version: String,
        packaging: String = "jar",
        classifier: String? = null,
    ): Path?
    fun findPom(group: String, artifact: String, version: String): Path?
    fun getExpectedLocation(
        group: String,
        artifact: String,
        version: String,
        packaging: String = "jar",
        classifier: String? = null,
    ): Path
    fun getExpectedPomLocation(group: String, artifact: String, version: String): Path
    fun verifyIntegrity(group: String, artifact: String, version: String, expectedChecksum: String?): Path?
    fun <T> withLock(key: String, block: () -> T): T
}

class LocalArtifactCache(
    private val customDir: Path? = null,
    private val customGradleCacheDir: Path? = null,
) : ArtifactCache {

    private val coordinateLocks = ConcurrentHashMap<String, Any>()

    override fun <T> withLock(key: String, block: () -> T): T {
        val lockObj = coordinateLocks.computeIfAbsent(key) { Any() }
        return synchronized(lockObj) {
            block()
        }
    }

    override fun getCacheDir(): Path {
        if (customDir != null) return customDir
        val home = System.getProperty("user.home") ?: "."
        return Path.of(home, ".qutivex", "cache").toAbsolutePath().normalize()
    }

    fun getArtifactsDir(): Path = getCacheDir().resolve("artifacts")
    fun getPomsDir(): Path = getCacheDir().resolve("poms")
    fun getMetadataDir(): Path = getCacheDir().resolve("metadata")
    fun getTempDir(): Path = getCacheDir().resolve("temp")

    private fun getGradleCacheDir(): Path {
        if (customGradleCacheDir != null) return customGradleCacheDir
        val home = System.getProperty("user.home") ?: "."
        return Path.of(home, ".gradle", "caches", "modules-2", "files-2.1").toAbsolutePath().normalize()
    }

    override fun contains(group: String, artifact: String, version: String): Boolean {
        return findArtifact(group, artifact, version) != null || findPom(group, artifact, version) != null
    }

    override fun findArtifact(
        group: String,
        artifact: String,
        version: String,
        packaging: String,
        classifier: String?,
    ): Path? {
        val jarName = buildArtifactFileName(artifact, version, packaging, classifier)

        // 1. Check native Qutivex artifacts cache: ~/.qutivex/cache/artifacts/<group>/<artifact>/<version>/<file>
        val nativeArtifact = getArtifactsDir().resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(jarName)
        if (Files.exists(nativeArtifact) && Files.size(nativeArtifact) > 0) return nativeArtifact

        // 2. Check legacy Qutivex cache: ~/.qutivex/cache/<group>/<artifact>/<version>/<file>
        val legacyArtifact = getCacheDir().resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(jarName)
        if (Files.exists(legacyArtifact) && Files.size(legacyArtifact) > 0) return legacyArtifact

        // 3. Check customDir direct or nested (e.g. for testing)
        if (customDir != null && Files.exists(customDir)) {
            val direct = customDir.resolve(jarName)
            if (Files.exists(direct) && Files.size(direct) > 0) return direct

            val customNested = customDir.resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(jarName)
            if (Files.exists(customNested) && Files.size(customNested) > 0) return customNested

            try {
                val found = Files.walk(customDir, 5)
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == jarName && Files.size(it) > 0 }
                    .findFirst()
                if (found.isPresent) return found.get()
            } catch (_: Exception) {}
        }

        // 4. Check Gradle files-2.1 cache as fallback
        val gradleDir = getGradleCacheDir().resolve(group).resolve(artifact).resolve(version)
        if (Files.exists(gradleDir) && Files.isDirectory(gradleDir)) {
            try {
                val found = Files.walk(gradleDir, 3)
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == jarName && Files.size(it) > 0 }
                    .findFirst()
                if (found.isPresent) return found.get()
            } catch (_: Exception) {}
        }

        return null
    }

    override fun findPom(group: String, artifact: String, version: String): Path? {
        val pomName = "$artifact-$version.pom"

        // 1. Check native Qutivex poms cache: ~/.qutivex/cache/poms/<group>/<artifact>/<version>/<file>
        val nativePom = getPomsDir().resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(pomName)
        if (Files.exists(nativePom) && Files.size(nativePom) > 0) return nativePom

        // 2. Check legacy Qutivex cache: ~/.qutivex/cache/<group>/<artifact>/<version>/<file>
        val legacyPom = getCacheDir().resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(pomName)
        if (Files.exists(legacyPom) && Files.size(legacyPom) > 0) return legacyPom

        // 3. Check customDir direct or nested
        if (customDir != null && Files.exists(customDir)) {
            val direct = customDir.resolve(pomName)
            if (Files.exists(direct) && Files.size(direct) > 0) return direct

            val customNested = customDir.resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(pomName)
            if (Files.exists(customNested) && Files.size(customNested) > 0) return customNested

            try {
                val found = Files.walk(customDir, 5)
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == pomName && Files.size(it) > 0 }
                    .findFirst()
                if (found.isPresent) return found.get()
            } catch (_: Exception) {}
        }

        // 4. Check Gradle files-2.1 cache
        val gradleDir = getGradleCacheDir().resolve(group).resolve(artifact).resolve(version)
        if (Files.exists(gradleDir) && Files.isDirectory(gradleDir)) {
            try {
                val found = Files.walk(gradleDir, 3)
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == pomName && Files.size(it) > 0 }
                    .findFirst()
                if (found.isPresent) return found.get()
            } catch (_: Exception) {}
        }

        return null
    }

    override fun getExpectedLocation(
        group: String,
        artifact: String,
        version: String,
        packaging: String,
        classifier: String?,
    ): Path {
        val fileName = buildArtifactFileName(artifact, version, packaging, classifier)
        return getArtifactsDir().resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(fileName)
    }

    override fun getExpectedPomLocation(group: String, artifact: String, version: String): Path {
        val fileName = "$artifact-$version.pom"
        return getPomsDir().resolve(group.replace('.', '/')).resolve(artifact).resolve(version).resolve(fileName)
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

    private fun buildArtifactFileName(artifact: String, version: String, packaging: String, classifier: String?): String {
        return if (classifier.isNullOrBlank()) {
            "$artifact-$version.$packaging"
        } else {
            "$artifact-$version-$classifier.$packaging"
        }
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
