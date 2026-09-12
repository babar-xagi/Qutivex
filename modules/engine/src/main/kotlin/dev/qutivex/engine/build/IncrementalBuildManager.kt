package dev.qutivex.engine.build

import dev.qutivex.core.manifest.ManifestToolchain
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

enum class BuildScope(val dirName: String, val fingerprintName: String) {
    MAIN("main", ".qutivex-main-fingerprint"),
    TEST("test", ".qutivex-test-fingerprint"),
}

sealed class IncrementalStatus {
    object UpToDate : IncrementalStatus()
    data class Stale(val reason: String) : IncrementalStatus()

    val isUpToDate: Boolean get() = this is UpToDate
}

/**
 * Manages incremental build fingerprints and cache validation.
 * Computes deterministic SHA-256 fingerprints across source files, resources,
 * classpath dependencies, and toolchain configurations.
 */
class IncrementalBuildManager {

    fun checkUpToDate(
        projectDir: Path,
        scope: BuildScope,
        sources: List<ScannedFile>,
        resources: List<ScannedFile>,
        classpath: List<Path>,
        toolchain: ManifestToolchain,
    ): IncrementalStatus {
        val classesDir = projectDir.resolve("build/classes/kotlin").resolve(scope.dirName)
        if (!Files.exists(classesDir) || !hasCompiledClasses(classesDir)) {
            return IncrementalStatus.Stale("Classes directory missing or empty: $classesDir")
        }

        val fingerprintFile = projectDir.resolve("build").resolve(scope.fingerprintName)
        if (!Files.exists(fingerprintFile)) {
            return IncrementalStatus.Stale("No previous build fingerprint found")
        }

        val currentFingerprint = computeFingerprint(sources, resources, classpath, toolchain)
        val savedFingerprint = try {
            Files.readString(fingerprintFile, UTF_8).trim()
        } catch (_: Exception) {
            return IncrementalStatus.Stale("Failed to read existing build fingerprint")
        }

        return if (savedFingerprint == currentFingerprint) {
            IncrementalStatus.UpToDate
        } else {
            IncrementalStatus.Stale("Inputs modified since last build")
        }
    }

    fun recordBuild(
        projectDir: Path,
        scope: BuildScope,
        sources: List<ScannedFile>,
        resources: List<ScannedFile>,
        classpath: List<Path>,
        toolchain: ManifestToolchain,
    ) {
        val buildDir = projectDir.resolve("build")
        Files.createDirectories(buildDir)

        val fingerprint = computeFingerprint(sources, resources, classpath, toolchain)
        val targetFile = buildDir.resolve(scope.fingerprintName)
        val tempFile = buildDir.resolve("${scope.fingerprintName}.tmp-${System.nanoTime()}")

        Files.writeString(tempFile, fingerprint, UTF_8)
        try {
            Files.move(tempFile, targetFile, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(tempFile, targetFile, REPLACE_EXISTING)
        }
    }

    fun computeFingerprint(
        sources: List<ScannedFile>,
        resources: List<ScannedFile>,
        classpath: List<Path>,
        toolchain: ManifestToolchain,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")

        // 1. Toolchain
        digest.update("toolchain:${toolchain.kotlin}:${toolchain.jvm}\n".toByteArray(UTF_8))

        // 2. Sources
        digest.update("sources:${sources.size}\n".toByteArray(UTF_8))
        for (src in sources) {
            digest.update("${src.relativePath}:${src.size}:${src.lastModified}:${src.sha256}\n".toByteArray(UTF_8))
        }

        // 3. Resources
        digest.update("resources:${resources.size}\n".toByteArray(UTF_8))
        for (res in resources) {
            digest.update("${res.relativePath}:${res.size}:${res.lastModified}:${res.sha256}\n".toByteArray(UTF_8))
        }

        // 4. Classpath
        digest.update("classpath:${classpath.size}\n".toByteArray(UTF_8))
        for (cp in classpath) {
            val name = cp.fileName?.toString() ?: cp.toString()
            val size = try { Files.size(cp) } catch (_: Exception) { 0L }
            val lastModified = try { Files.getLastModifiedTime(cp).toMillis() } catch (_: Exception) { 0L }
            digest.update("$name:$size:$lastModified\n".toByteArray(UTF_8))
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasCompiledClasses(dir: Path): Boolean {
        if (!dir.isDirectory()) return false
        return Files.walk(dir)
            .anyMatch { it.isRegularFile() && it.fileName.toString().endsWith(".class") }
    }
}
