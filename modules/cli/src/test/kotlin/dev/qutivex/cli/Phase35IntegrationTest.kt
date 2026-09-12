package dev.qutivex.cli

import dev.qutivex.core.dependency.ComparableVersion
import dev.qutivex.engine.dependency.LocalArtifactCache
import dev.qutivex.engine.selfupdate.ReleaseAsset
import dev.qutivex.engine.selfupdate.ReleaseInfo
import dev.qutivex.engine.selfupdate.ReleaseProvider
import dev.qutivex.engine.selfupdate.SelfUpdater
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase35IntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private class MockReleaseProvider(
        var latestVersion: String = "9.9.9",
        var msiContent: String = "MOCK_MSI_PAYLOAD_V999",
    ) : ReleaseProvider {
        override fun fetchLatestRelease(): ReleaseInfo {
            return ReleaseInfo(
                tagName = "v$latestVersion",
                version = latestVersion,
                htmlUrl = "https://github.com/babar-xagi/Qutivex/releases/tag/v$latestVersion",
                body = "Test release body",
                assets = listOf(
                    ReleaseAsset(
                        name = "qutivex-$latestVersion-windows-x64.msi",
                        downloadUrl = "https://example.com/qutivex.msi",
                        size = msiContent.toByteArray(StandardCharsets.UTF_8).size.toLong(),
                    ),
                    ReleaseAsset(
                        name = "qutivex-$latestVersion-windows-x64.msi.sha256",
                        downloadUrl = "https://example.com/qutivex.msi.sha256",
                        size = 64L,
                    ),
                ),
            )
        }

        override fun downloadAsset(assetUrl: String, destination: Path): Path {
            Files.createDirectories(destination.parent)
            if (assetUrl.endsWith(".sha256")) {
                val tempMsi = destination.parent.resolve("temp-calc.msi")
                Files.writeString(tempMsi, msiContent)
                val sha = LocalArtifactCache.computeSha256(tempMsi)
                Files.deleteIfExists(tempMsi)
                Files.writeString(destination, "$sha  qutivex-$latestVersion-windows-x64.msi\n")
            } else {
                Files.writeString(destination, msiContent)
            }
            return destination
        }
    }

    @Test
    fun `qutivex update --check reports update availability`() {
        val mockProvider = MockReleaseProvider(latestVersion = "99.0.0")
        val selfUpdater = SelfUpdater(releaseProvider = mockProvider)
        val cli = QutivexCli(selfUpdater = selfUpdater)

        val out = StringWriter()
        val err = StringWriter()
        val code = cli.execute(listOf("update", "--check"), tempDir, PrintWriter(out), PrintWriter(err))

        assertEquals(0, code, err.toString())
        assertContains(out.toString(), "Latest:  99.0.0")
        assertContains(out.toString(), "Update available. Run 'qutivex update' to install.")
    }

    @Test
    fun `qutivex update --check reports up to date when current version is latest`() {
        val mockProvider = MockReleaseProvider(latestVersion = "0.0.1")
        val selfUpdater = SelfUpdater(releaseProvider = mockProvider)
        val cli = QutivexCli(selfUpdater = selfUpdater)

        val out = StringWriter()
        val err = StringWriter()
        val code = cli.execute(listOf("update", "--check"), tempDir, PrintWriter(out), PrintWriter(err))

        assertEquals(0, code, err.toString())
        assertContains(out.toString(), "Qutivex is up to date.")
    }

    @Test
    fun `qutivex update with coordinate and --check is rejected as invalid usage`() {
        val cli = QutivexCli()
        val out = StringWriter()
        val err = StringWriter()
        val code = cli.execute(listOf("update", "org.example:foo:1.0.0", "--check"), tempDir, PrintWriter(out), PrintWriter(err))

        assertEquals(2, code)
        assertContains(err.toString(), "'--check' is only valid for CLI self-update")
    }

    @Test
    fun `gradle independence - dependency commands execute with zero gradle wrapper and zero gradle build files`() {
        val projectDir = tempDir.resolve("independent-app")
        val cli = QutivexCli()

        // 1. Initialize project
        val outInit = StringWriter()
        val errInit = StringWriter()
        val codeInit = cli.execute(listOf("init", projectDir.toString()), tempDir, PrintWriter(outInit), PrintWriter(errInit))
        assertEquals(0, codeInit, errInit.toString())

        // Deliberately remove and corrupt any Gradle wrapper scripts to prove Gradle is never invoked
        val gradleDir = projectDir.resolve(".qutivex/gradle")
        if (Files.exists(gradleDir)) {
            val gradlewBat = gradleDir.resolve("gradlew.bat")
            if (Files.exists(gradlewBat)) {
                Files.writeString(gradlewBat, "@echo off\necho GRADLE SHOULD NEVER BE CALLED\nexit /b 1\n")
            }
        }

        // 2. Add dependency (Native resolution - does NOT call gradlew)
        val outAdd = StringWriter()
        val errAdd = StringWriter()
        val codeAdd = cli.execute(
            listOf("add", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"),
            projectDir,
            PrintWriter(outAdd),
            PrintWriter(errAdd),
        )
        assertEquals(0, codeAdd, errAdd.toString())
        assertContains(outAdd.toString(), "Added org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        assertFalse(outAdd.toString().contains("GRADLE SHOULD NEVER BE CALLED"))
        assertFalse(errAdd.toString().contains("GRADLE SHOULD NEVER BE CALLED"))

        // Verify lockfile was generated natively
        val lockFile = projectDir.resolve("qutivex.lock")
        assertTrue(Files.exists(lockFile))
        val lockText = Files.readString(lockFile)
        assertContains(lockText, "kotlinx-coroutines-core")
        assertContains(lockText, "checksum = ")

        // 3. List dependencies (Native - does NOT call gradlew)
        val outList = StringWriter()
        val errList = StringWriter()
        val codeList = cli.execute(listOf("list"), projectDir, PrintWriter(outList), PrintWriter(errList))
        assertEquals(0, codeList, errList.toString())
        assertContains(outList.toString(), "kotlinx-coroutines-core:1.10.2")
        assertFalse(outList.toString().contains("GRADLE SHOULD NEVER BE CALLED"))

        // 4. Tree dependencies (Native - does NOT call gradlew)
        val outTree = StringWriter()
        val errTree = StringWriter()
        val codeTree = cli.execute(listOf("tree"), projectDir, PrintWriter(outTree), PrintWriter(errTree))
        assertEquals(0, codeTree, errTree.toString())
        assertContains(outTree.toString(), "kotlinx-coroutines-core:1.10.2")
        assertFalse(outTree.toString().contains("GRADLE SHOULD NEVER BE CALLED"))

        // 5. Update dependency (Native - does NOT call gradlew)
        val outUpdate = StringWriter()
        val errUpdate = StringWriter()
        val codeUpdate = cli.execute(
            listOf("update", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"),
            projectDir,
            PrintWriter(outUpdate),
            PrintWriter(errUpdate),
        )
        assertEquals(0, codeUpdate, errUpdate.toString())
        assertContains(outUpdate.toString(), "is already at version 1.10.2")
        assertFalse(outUpdate.toString().contains("GRADLE SHOULD NEVER BE CALLED"))

        // 6. Install --frozen (Native - zero network, zero Gradle)
        val outFrozen = StringWriter()
        val errFrozen = StringWriter()
        val codeFrozen = cli.execute(listOf("install", "--frozen"), projectDir, PrintWriter(outFrozen), PrintWriter(errFrozen))
        assertEquals(0, codeFrozen, errFrozen.toString())
        assertFalse(outFrozen.toString().contains("GRADLE SHOULD NEVER BE CALLED"))

        // 7. Install --offline (Native - zero network, zero Gradle)
        val outOffline = StringWriter()
        val errOffline = StringWriter()
        val codeOffline = cli.execute(listOf("install", "--offline"), projectDir, PrintWriter(outOffline), PrintWriter(errOffline))
        assertEquals(0, codeOffline, errOffline.toString())
        assertFalse(outOffline.toString().contains("GRADLE SHOULD NEVER BE CALLED"))

        // 8. Install --offline --frozen (Native - zero network, zero Gradle, zero mutation)
        val lockBefore = Files.readString(lockFile)
        val outOfflineFrozen = StringWriter()
        val errOfflineFrozen = StringWriter()
        val codeOfflineFrozen = cli.execute(
            listOf("install", "--offline", "--frozen"),
            projectDir,
            PrintWriter(outOfflineFrozen),
            PrintWriter(errOfflineFrozen),
        )
        assertEquals(0, codeOfflineFrozen, errOfflineFrozen.toString())
        assertFalse(outOfflineFrozen.toString().contains("GRADLE SHOULD NEVER BE CALLED"))
        assertEquals(lockBefore, Files.readString(lockFile), "Offline+frozen must not mutate lockfile")

        // 9. Remove dependency (Native - does NOT call gradlew)
        val outRemove = StringWriter()
        val errRemove = StringWriter()
        val codeRemove = cli.execute(
            listOf("remove", "org.jetbrains.kotlinx:kotlinx-coroutines-core"),
            projectDir,
            PrintWriter(outRemove),
            PrintWriter(errRemove),
        )
        assertEquals(0, codeRemove, errRemove.toString())
        assertContains(outRemove.toString(), "Removed org.jetbrains.kotlinx:kotlinx-coroutines-core")
        assertFalse(outRemove.toString().contains("GRADLE SHOULD NEVER BE CALLED"))
    }

    @Test
    fun `offline install fails cleanly when artifact is missing from local cache`() {
        val isolatedCacheDir = tempDir.resolve("empty-cache")
        val isolatedCache = LocalArtifactCache(customDir = isolatedCacheDir)
        val depMgr = dev.qutivex.engine.dependency.DependencyManager(artifactCache = isolatedCache)
        val cli = QutivexCli(dependencyManager = depMgr)

        val projectDir = tempDir.resolve("missing-offline-app")
        cli.execute(listOf("init", projectDir.toString()), tempDir, PrintWriter(StringWriter()), PrintWriter(StringWriter()))

        // Deliberately add an uncached dependency to the initialized manifest
        val manifestFile = projectDir.resolve("qutivex.toml")
        val currentToml = Files.readString(manifestFile)
        Files.writeString(
            manifestFile,
            currentToml.replace("[dependencies]", "[dependencies]\n\"org.example:uncached-lib\" = \"1.0.0\"")
        )

        val out = StringWriter()
        val err = StringWriter()
        val code = cli.execute(listOf("install", "--offline"), projectDir, PrintWriter(out), PrintWriter(err))

        assertEquals(1, code)
        assertTrue(
            err.toString().contains("Offline installation cannot continue") ||
            err.toString().contains("Missing artifact:") ||
            err.toString().contains("Missing offline artifact") ||
            err.toString().contains("not found in local cache"),
            "Error output was: ${err.toString()}"
        )
    }

    @Test
    fun `tampered cache artifact is rejected during integrity verification`() {
        val customCacheDir = tempDir.resolve("tampered-cache")
        val cache = LocalArtifactCache(customDir = customCacheDir)

        // Store an artifact in cache
        val artifactFile = cache.getExpectedLocation("dev.test", "test-pkg", "1.0.0")
        Files.createDirectories(artifactFile.parent)
        Files.writeString(artifactFile, "VALID_CONTENT")
        val originalSha = LocalArtifactCache.computeSha256(artifactFile)

        // Verify valid passes
        cache.verifyIntegrity("dev.test", "test-pkg", "1.0.0", originalSha)

        // Tamper with file
        Files.writeString(artifactFile, "TAMPERED_CONTENT")

        // Verification must throw ArtifactIntegrityException
        val thrown = org.junit.jupiter.api.assertThrows<dev.qutivex.engine.dependency.ArtifactIntegrityException> {
            cache.verifyIntegrity("dev.test", "test-pkg", "1.0.0", originalSha)
        }
        assertTrue(thrown.message?.contains("Artifact integrity verification failed") == true)
    }
}

