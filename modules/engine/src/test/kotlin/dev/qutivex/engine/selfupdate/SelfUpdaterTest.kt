package dev.qutivex.engine.selfupdate

import dev.qutivex.engine.dependency.LocalArtifactCache
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfUpdaterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `checkUpdate detects available updates`() {
        val fakeProvider = FakeReleaseProvider(
            releaseInfo = ReleaseInfo(
                tagName = "v0.3.1",
                version = "0.3.1",
                htmlUrl = "https://github.com/babar-xagi/Qutivex/releases/tag/v0.3.1",
                body = "Release notes",
                assets = emptyList(),
            )
        )

        val updater = SelfUpdater(releaseProvider = fakeProvider, osName = "Windows 11")
        val check = updater.checkUpdate("0.3.0")

        assertTrue(check.updateAvailable)
        assertEquals("0.3.0", check.currentVersion)
        assertEquals("0.3.1", check.latestVersion)
    }

    @Test
    fun `checkUpdate reports up to date when current version is equal or newer`() {
        val fakeProvider = FakeReleaseProvider(
            releaseInfo = ReleaseInfo(
                tagName = "v0.3.0",
                version = "0.3.0",
                htmlUrl = "https://github.com/babar-xagi/Qutivex/releases/tag/v0.3.0",
                body = "Release notes",
                assets = emptyList(),
            )
        )

        val updater = SelfUpdater(releaseProvider = fakeProvider, osName = "Windows 11")
        val check = updater.checkUpdate("0.3.0")
        assertFalse(check.updateAvailable)
    }

    @Test
    fun `executeUpdate downloads and verifies valid MSI checksum`() {
        val msiContent = "sample-windows-msi-binary"
        val msiFile = tempDir.resolve("qutivex-x64.msi")
        Files.writeString(msiFile, msiContent)
        val validSha = LocalArtifactCache.computeSha256(msiFile)

        val fakeProvider = FakeReleaseProvider(
            releaseInfo = ReleaseInfo(
                tagName = "v0.3.1",
                version = "0.3.1",
                htmlUrl = "https://github.com/babar-xagi/Qutivex/releases/tag/v0.3.1",
                body = "Release notes",
                assets = listOf(
                    ReleaseAsset("qutivex-x64.msi", "https://download/qutivex-x64.msi", 1000L),
                    ReleaseAsset("qutivex-x64.msi.sha256", "https://download/qutivex-x64.msi.sha256", 64L),
                ),
            ),
            msiContent = msiContent,
            shaContent = "$validSha  qutivex-x64.msi\n",
        )

        val updater = SelfUpdater(releaseProvider = fakeProvider, osName = "Windows 11")
        val out = StringWriter()
        val err = StringWriter()

        var launchedPath: Path? = null
        val exitCode = updater.executeUpdate(
            currentVersion = "0.3.0",
            stdout = PrintWriter(out),
            stderr = PrintWriter(err),
            installerLauncher = { path ->
                launchedPath = path
                val isWin = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
                if (isWin) {
                    ProcessBuilder("cmd.exe", "/c", "echo ok").start()
                } else {
                    ProcessBuilder("sh", "-c", "echo ok").start()
                }
            },
        )

        assertEquals(0, exitCode)
        assertTrue(out.toString().contains("Verified MSI checksum"))
        assertTrue(out.toString().contains("Launching Windows Installer"))
        assertTrue(launchedPath != null)
    }

    @Test
    fun `executeUpdate rejects tampered MSI checksum`() {
        val fakeProvider = FakeReleaseProvider(
            releaseInfo = ReleaseInfo(
                tagName = "v0.3.1",
                version = "0.3.1",
                htmlUrl = "https://github.com/babar-xagi/Qutivex/releases/tag/v0.3.1",
                body = "Release notes",
                assets = listOf(
                    ReleaseAsset("qutivex-x64.msi", "https://download/qutivex-x64.msi", 1000L),
                    ReleaseAsset("qutivex-x64.msi.sha256", "https://download/qutivex-x64.msi.sha256", 64L),
                ),
            ),
            msiContent = "tampered-msi-content",
            shaContent = "0000000000000000000000000000000000000000000000000000000000000000  qutivex-x64.msi\n",
        )

        val updater = SelfUpdater(releaseProvider = fakeProvider, osName = "Windows 11")
        val out = StringWriter()
        val err = StringWriter()

        assertFailsWith<SecurityException> {
            updater.executeUpdate(
                currentVersion = "0.3.0",
                stdout = PrintWriter(out),
                stderr = PrintWriter(err),
            )
        }
    }

    private class FakeReleaseProvider(
        val releaseInfo: ReleaseInfo,
        val msiContent: String = "dummy-msi",
        val shaContent: String = "dummy-sha",
    ) : ReleaseProvider {
        override fun fetchLatestRelease(): ReleaseInfo = releaseInfo

        override fun downloadAsset(assetUrl: String, destination: Path): Path {
            Files.createDirectories(destination.parent)
            if (destination.fileName.toString().endsWith(".sha256")) {
                Files.writeString(destination, shaContent)
            } else {
                Files.writeString(destination, msiContent)
            }
            return destination
        }
    }
}
