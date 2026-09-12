package dev.qutivex.engine.selfupdate

import dev.qutivex.core.dependency.ComparableVersion
import dev.qutivex.engine.dependency.LocalArtifactCache
import java.io.PrintWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
)

data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val htmlUrl: String,
    val body: String,
    val assets: List<ReleaseAsset>,
)

data class UpdateCheckResult(
    val currentVersion: String,
    val latestVersion: String,
    val updateAvailable: Boolean,
    val release: ReleaseInfo,
)

interface ReleaseProvider {
    fun fetchLatestRelease(): ReleaseInfo
    fun downloadAsset(assetUrl: String, destination: Path): Path
}

class GitHubReleaseProvider(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val repoOwner: String = "babar-xagi",
    private val repoName: String = "Qutivex",
) : ReleaseProvider {

    override fun fetchLatestRelease(): ReleaseInfo {
        val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Qutivex-SelfUpdater")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Failed to query latest release from GitHub (HTTP ${response.statusCode()})")
        }

        return parseReleaseJson(response.body())
    }

    override fun downloadAsset(assetUrl: String, destination: Path): Path {
        val parent = destination.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val temp = parent.resolve("${destination.fileName}.tmp.${UUID.randomUUID()}")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(assetUrl))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Qutivex-SelfUpdater")
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("Failed to download asset from $assetUrl (HTTP ${response.statusCode()})")
            }
            response.body().use { input ->
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            return destination
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        fun parseReleaseJson(json: String): ReleaseInfo {
            val tagName = extractJsonString(json, "tag_name") ?: "v0.0.0"
            val version = tagName.removePrefix("v").trim()
            val htmlUrl = extractJsonString(json, "html_url") ?: ""
            val body = extractJsonString(json, "body") ?: ""

            val assets = mutableListOf<ReleaseAsset>()
            val assetBlocks = json.split(""""content_type":""")
            for (block in assetBlocks.drop(1)) {
                val name = extractJsonString(block, "name")
                val downloadUrl = extractJsonString(block, "browser_download_url")
                val sizeMatch = Regex(""""size"\s*:\s*(\d+)""").find(block)
                val size = sizeMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (name != null && downloadUrl != null) {
                    assets.add(ReleaseAsset(name, downloadUrl, size))
                }
            }

            return ReleaseInfo(
                tagName = tagName,
                version = version,
                htmlUrl = htmlUrl,
                body = body,
                assets = assets,
            )
        }

        private fun extractJsonString(json: String, key: String): String? {
            val pattern = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
            val match = pattern.find(json) ?: return null
            return match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
    }
}

class SelfUpdater(
    private val releaseProvider: ReleaseProvider = GitHubReleaseProvider(),
    private val osName: String = System.getProperty("os.name") ?: "",
) {
    val isWindows: Boolean = osName.lowercase().contains("windows")

    fun checkUpdate(currentVersion: String): UpdateCheckResult {
        val release = releaseProvider.fetchLatestRelease()
        val latest = ComparableVersion(release.version)
        val current = ComparableVersion(currentVersion)
        val updateAvailable = latest > current
        return UpdateCheckResult(
            currentVersion = currentVersion,
            latestVersion = release.version,
            updateAvailable = updateAvailable,
            release = release,
        )
    }

    fun executeUpdate(
        currentVersion: String,
        stdout: PrintWriter,
        stderr: PrintWriter,
        dryRun: Boolean = false,
        installerLauncher: ((Path) -> Process)? = null,
    ): Int {
        val check = checkUpdate(currentVersion)
        if (!check.updateAvailable) {
            stdout.println("✨ Qutivex is already up to date (version $currentVersion).")
            return 0
        }

        val release = check.release
        stdout.println("🚀 Updating Qutivex: $currentVersion -> ${check.latestVersion}")

        if (isWindows) {
            val msiAsset = release.assets.firstOrNull { it.name.endsWith(".msi") }
                ?: throw IllegalStateException("No Windows MSI asset found in release ${release.tagName}.")

            val shaAsset = release.assets.firstOrNull { it.name.endsWith(".msi.sha256") }

            val tempDir = Path.of(System.getProperty("java.io.tmpdir"), "qutivex-update-${System.currentTimeMillis()}")
            Files.createDirectories(tempDir)
            val msiPath = tempDir.resolve(msiAsset.name)

            stdout.println("📥 Downloading ${msiAsset.name}...")
            releaseProvider.downloadAsset(msiAsset.downloadUrl, msiPath)

            // Verify checksum
            if (shaAsset != null) {
                val shaPath = tempDir.resolve(shaAsset.name)
                releaseProvider.downloadAsset(shaAsset.downloadUrl, shaPath)
                val rawSha = Files.readString(shaPath).trim()
                val expectedSha = rawSha.split(Regex("""\s+""")).first().lowercase()
                val actualSha = LocalArtifactCache.computeSha256(msiPath).lowercase()

                if (actualSha != expectedSha) {
                    Files.deleteIfExists(msiPath)
                    throw SecurityException(
                        "Installer integrity verification failed.\nExpected SHA-256: $expectedSha\nActual SHA-256:   $actualSha\nUpdate aborted."
                    )
                }
                stdout.println("🔒 Verified MSI checksum (SHA-256: $actualSha)")
            }

            if (dryRun) {
                stdout.println("✨ Verified update installer for Qutivex ${check.latestVersion}.")
                return 0
            }

            stdout.println("📦 Launching Windows Installer for Qutivex ${check.latestVersion}...")
            if (installerLauncher != null) {
                installerLauncher(msiPath)
            } else {
                ProcessBuilder("msiexec.exe", "/i", msiPath.toAbsolutePath().toString(), "/qb")
                    .start()
            }
            return 0
        } else {
            // Linux / macOS
            val archiveAsset = release.assets.firstOrNull { it.name.endsWith(".tar.gz") || it.name.endsWith(".zip") }
            if (archiveAsset != null) {
                stdout.println("📦 New release package available: ${archiveAsset.downloadUrl}")
            }
            stdout.println("🔗 Release page: ${release.htmlUrl}")
            return 0
        }
    }
}
