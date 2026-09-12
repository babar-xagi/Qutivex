package dev.qutivex.engine.dependency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactCacheTest {

    @TempDir
    lateinit var cacheDir: Path

    @Test
    fun `findArtifact locates artifact in cache and contains returns true`() {
        val cache = LocalArtifactCache(customDir = cacheDir)
        assertFalse(cache.contains("org.example", "my-lib", "1.0.0"))

        val jarPath = cacheDir.resolve("my-lib-1.0.0.jar")
        Files.writeString(jarPath, "dummy-jar-content")

        assertTrue(cache.contains("org.example", "my-lib", "1.0.0"))
        val found = cache.findArtifact("org.example", "my-lib", "1.0.0")
        assertEquals(jarPath, found)
    }

    @Test
    fun `verifyIntegrity verifies valid checksum and rejects tampered bytes`() {
        val cache = LocalArtifactCache(customDir = cacheDir)
        val jarPath = cacheDir.resolve("my-lib-1.0.0.jar")
        Files.writeString(jarPath, "authentic-content")

        val expectedSha = LocalArtifactCache.computeSha256(jarPath)
        val verified = cache.verifyIntegrity("org.example", "my-lib", "1.0.0", "sha256:$expectedSha")
        assertEquals(jarPath, verified)

        // Now tamper with the artifact file
        Files.writeString(jarPath, "corrupted-content-tampered-bytes")
        val ex = assertThrows<ArtifactIntegrityException> {
            cache.verifyIntegrity("org.example", "my-lib", "1.0.0", "sha256:$expectedSha")
        }

        assertEquals("org.example", ex.group)
        assertEquals("my-lib", ex.artifact)
        assertEquals("1.0.0", ex.version)
        assertEquals(expectedSha, ex.expectedSha256)
        assertTrue(ex.message!!.contains("Artifact integrity verification failed"))
        assertTrue(ex.message!!.contains("Expected SHA-256:"))
        assertTrue(ex.message!!.contains("Actual SHA-256:"))
        assertTrue(ex.message!!.contains("The cached artifact may be corrupted or modified."))
    }

    @Test
    fun `missing artifact exception reports coordinate and expected location`() {
        val cache = LocalArtifactCache(customDir = cacheDir)
        val expectedLoc = cache.getExpectedLocation("org.example", "missing-lib", "2.0.0")

        val ex = MissingOfflineArtifactException("org.example", "missing-lib", "2.0.0", expectedLoc)
        assertEquals("org.example", ex.group)
        assertEquals("missing-lib", ex.artifact)
        assertEquals("2.0.0", ex.version)
        assertTrue(ex.message!!.contains("Offline installation cannot continue."))
        assertTrue(ex.message!!.contains("Missing artifact:\norg.example:missing-lib:2.0.0"))
        assertTrue(ex.message!!.contains("Expected cache location:"))
        assertTrue(ex.message!!.contains("Run without --offline when network access is available."))
    }
}
