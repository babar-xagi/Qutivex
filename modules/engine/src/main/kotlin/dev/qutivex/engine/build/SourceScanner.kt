package dev.qutivex.engine.build

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

data class ScannedFile(
    val file: Path,
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
) {
    val sha256: String by lazy {
        computeSha256(file)
    }

    companion object {
        fun computeSha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input: InputStream ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class SourceScanResult(
    val mainSources: List<ScannedFile>,
    val mainResources: List<ScannedFile>,
    val testSources: List<ScannedFile>,
    val testResources: List<ScannedFile>,
) {
    val hasMainSources: Boolean get() = mainSources.isNotEmpty()
    val hasTestSources: Boolean get() = testSources.isNotEmpty()
    val hasMainResources: Boolean get() = mainResources.isNotEmpty()
    val hasTestResources: Boolean get() = testResources.isNotEmpty()
}

/**
 * Scans a project directory for main and test Kotlin sources and resources.
 */
class SourceScanner {

    fun scan(projectDir: Path): SourceScanResult {
        val mainSrcDir = projectDir.resolve("src/main/kotlin")
        val mainResDir = projectDir.resolve("src/main/resources")
        val testSrcDir = projectDir.resolve("src/test/kotlin")
        val testResDir = projectDir.resolve("src/test/resources")

        val mainSources = scanSourceFiles(mainSrcDir)
        val mainResources = scanResourceFiles(mainResDir)
        val testSources = scanSourceFiles(testSrcDir)
        val testResources = scanResourceFiles(testResDir)

        return SourceScanResult(
            mainSources = mainSources,
            mainResources = mainResources,
            testSources = testSources,
            testResources = testResources,
        )
    }

    private fun scanSourceFiles(dir: Path): List<ScannedFile> {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return emptyList()

        return Files.walk(dir).use { stream ->
            stream.filter { it.isRegularFile() && isSourceFile(it) }
                .map { path ->
                    val rel = path.relativeTo(dir).toString().replace('\\', '/')
                    val attrs = Files.readAttributes(path, "basic:size,lastModifiedTime")
                    val size = (attrs["size"] as? Number)?.toLong() ?: Files.size(path)
                    val lastModified = (attrs["lastModifiedTime"] as? java.nio.file.attribute.FileTime)?.toMillis() ?: 0L
                    ScannedFile(
                        file = path,
                        relativePath = rel,
                        size = size,
                        lastModified = lastModified,
                    )
                }
                .toList()
        }.sortedBy { it.relativePath }
    }

    private fun scanResourceFiles(dir: Path): List<ScannedFile> {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return emptyList()

        return Files.walk(dir).use { stream ->
            stream.filter { it.isRegularFile() && !isIgnored(it) }
                .map { path ->
                    val rel = path.relativeTo(dir).toString().replace('\\', '/')
                    val attrs = Files.readAttributes(path, "basic:size,lastModifiedTime")
                    val size = (attrs["size"] as? Number)?.toLong() ?: Files.size(path)
                    val lastModified = (attrs["lastModifiedTime"] as? java.nio.file.attribute.FileTime)?.toMillis() ?: 0L
                    ScannedFile(
                        file = path,
                        relativePath = rel,
                        size = size,
                        lastModified = lastModified,
                    )
                }
                .toList()
        }.sortedBy { it.relativePath }
    }

    private fun isSourceFile(path: Path): Boolean {
        val name = path.fileName.toString()
        if (name.startsWith(".")) return false
        return name.endsWith(".kt") || name.endsWith(".kts") || name.endsWith(".java")
    }

    private fun isIgnored(path: Path): Boolean {
        val name = path.fileName.toString()
        return name.startsWith(".") || name.endsWith(".tmp")
    }
}
