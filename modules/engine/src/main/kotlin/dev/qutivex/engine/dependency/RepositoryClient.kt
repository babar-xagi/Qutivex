package dev.qutivex.engine.dependency

import dev.qutivex.core.dependency.MavenCoordinate
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

data class DownloadResult(
    val file: Path,
    val sha256: String,
    val bytesRead: Long,
)

open class RepositoryException(message: String, cause: Throwable? = null) : DependencyException(message, cause)

class ArtifactNotFoundException(
    val group: String,
    val artifact: String,
    val version: String,
    val repositoryUrl: String,
    message: String = "Artifact '$group:$artifact:$version' was not found in repository '$repositoryUrl'.",
) : RepositoryException(message)

/**
 * Abstraction for communicating with remote Maven repositories.
 */
interface RepositoryClient {
    fun isReachable(repositoryUrl: String = DEFAULT_REPOSITORY): Boolean

    fun fetchPom(
        group: String,
        artifact: String,
        version: String,
        destination: Path,
        repositoryUrl: String = DEFAULT_REPOSITORY,
    ): DownloadResult

    fun fetchArtifact(
        group: String,
        artifact: String,
        version: String,
        packaging: String = "jar",
        classifier: String? = null,
        destination: Path,
        repositoryUrl: String = DEFAULT_REPOSITORY,
    ): DownloadResult

    fun fetchMetadata(
        group: String,
        artifact: String,
        destination: Path,
        repositoryUrl: String = DEFAULT_REPOSITORY,
    ): DownloadResult

    fun fetchText(url: String): String

    companion object {
        const val DEFAULT_REPOSITORY = "https://repo.maven.apache.org/maven2/"
    }
}

class HttpRepositoryClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val userAgent: String = "Qutivex/0.3.5 (Java ${System.getProperty("java.version")}; ${System.getProperty("os.name")})",
) : RepositoryClient {

    override fun isReachable(repositoryUrl: String): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(repositoryUrl))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(3))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            response.statusCode() in 200..399
        } catch (_: Exception) {
            false
        }
    }

    override fun fetchPom(
        group: String,
        artifact: String,
        version: String,
        destination: Path,
        repositoryUrl: String,
    ): DownloadResult {
        val relPath = MavenCoordinate(group, artifact, version).repoRelativePomPath
        val normalizedBase = if (repositoryUrl.endsWith('/')) repositoryUrl else "$repositoryUrl/"
        val url = normalizedBase + relPath
        return downloadToFile(url, destination, group, artifact, version, repositoryUrl)
    }

    override fun fetchArtifact(
        group: String,
        artifact: String,
        version: String,
        packaging: String,
        classifier: String?,
        destination: Path,
        repositoryUrl: String,
    ): DownloadResult {
        val relPath = MavenCoordinate(group, artifact, version, packaging, classifier).repoRelativeArtifactPath
        val normalizedBase = if (repositoryUrl.endsWith('/')) repositoryUrl else "$repositoryUrl/"
        val url = normalizedBase + relPath
        return downloadToFile(url, destination, group, artifact, version, repositoryUrl)
    }

    override fun fetchMetadata(
        group: String,
        artifact: String,
        destination: Path,
        repositoryUrl: String,
    ): DownloadResult {
        val relPath = MavenCoordinate(group, artifact, "metadata").repoRelativeMetadataPath
        val normalizedBase = if (repositoryUrl.endsWith('/')) repositoryUrl else "$repositoryUrl/"
        val url = normalizedBase + relPath
        return downloadToFile(url, destination, group, artifact, "", repositoryUrl)
    }

    override fun fetchText(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw RepositoryException("HTTP ${response.statusCode()} while fetching $url")
        }
        return response.body()
    }

    private fun downloadToFile(
        url: String,
        destination: Path,
        group: String,
        artifact: String,
        version: String,
        repositoryUrl: String,
        retries: Int = 3,
    ): DownloadResult {
        val parent = destination.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val tempFile = parent.resolve("${destination.fileName}.tmp.${UUID.randomUUID()}")

        var attempt = 0
        var lastError: Exception? = null

        while (attempt < retries) {
            attempt++
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val status = response.statusCode()

                if (status == 404) {
                    throw ArtifactNotFoundException(group, artifact, version, repositoryUrl)
                }
                if (status in 500..599) {
                    if (attempt < retries) {
                        Thread.sleep(100L * attempt)
                        continue
                    }
                }
                if (status !in 200..299) {
                    throw RepositoryException("HTTP $status while downloading $url")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var totalBytes = 0L

                response.body().use { input ->
                    Files.newOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            totalBytes += read
                        }
                    }
                }

                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

                try {
                    Files.move(tempFile, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING)
                }

                return DownloadResult(destination, sha256, totalBytes)
            } catch (e: ArtifactNotFoundException) {
                Files.deleteIfExists(tempFile)
                throw e
            } catch (e: Exception) {
                Files.deleteIfExists(tempFile)
                lastError = e
                if (attempt < retries) {
                    try {
                        Thread.sleep(150L * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        throw RepositoryException("Failed to download '$url' after $retries attempts: ${lastError?.message}", lastError)
    }
}
