package dev.qutivex.engine.build

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceScannerTest {

    @TempDir
    lateinit var tempDir: Path

    private val scanner = SourceScanner()

    @Test
    fun `scans main and test sources and resources correctly`() {
        val mainSrc = tempDir.resolve("src/main/kotlin/com/example")
        val mainRes = tempDir.resolve("src/main/resources/config")
        val testSrc = tempDir.resolve("src/test/kotlin/com/example")
        val testRes = tempDir.resolve("src/test/resources/fixtures")

        Files.createDirectories(mainSrc)
        Files.createDirectories(mainRes)
        Files.createDirectories(testSrc)
        Files.createDirectories(testRes)

        Files.writeString(mainSrc.resolve("Main.kt"), "fun main() {}")
        Files.writeString(mainSrc.resolve("Helper.kt"), "class Helper")
        Files.writeString(mainRes.resolve("app.properties"), "key=value")
        Files.writeString(testSrc.resolve("MainTest.kt"), "class MainTest")
        Files.writeString(testRes.resolve("test-data.json"), "{}")

        // Ignored files
        Files.writeString(mainSrc.resolve(".hidden.kt"), "fun hidden() {}")
        Files.writeString(mainRes.resolve("temp.tmp"), "temp")

        val result = scanner.scan(tempDir)

        assertTrue(result.hasMainSources)
        assertEquals(2, result.mainSources.size)
        assertEquals("com/example/Helper.kt", result.mainSources[0].relativePath)
        assertEquals("com/example/Main.kt", result.mainSources[1].relativePath)

        assertTrue(result.hasMainResources)
        assertEquals(1, result.mainResources.size)
        assertEquals("config/app.properties", result.mainResources[0].relativePath)

        assertTrue(result.hasTestSources)
        assertEquals(1, result.testSources.size)
        assertEquals("com/example/MainTest.kt", result.testSources[0].relativePath)

        assertTrue(result.hasTestResources)
        assertEquals(1, result.testResources.size)
        assertEquals("fixtures/test-data.json", result.testResources[0].relativePath)
    }

    @Test
    fun `handles empty project directory gracefully`() {
        val result = scanner.scan(tempDir)

        assertFalse(result.hasMainSources)
        assertFalse(result.hasMainResources)
        assertFalse(result.hasTestSources)
        assertFalse(result.hasTestResources)
        assertEquals(0, result.mainSources.size)
    }

    @Test
    fun `computes deterministic sha256 for scanned files`() {
        val file = tempDir.resolve("test.txt")
        Files.writeString(file, "hello world")

        val hash1 = ScannedFile.computeSha256(file)
        val hash2 = ScannedFile.computeSha256(file)
        assertEquals(hash1, hash2)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash1)
    }
}
