package dev.qutivex.engine.build

import dev.qutivex.core.manifest.ManifestToolchain
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncrementalBuildManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val manager = IncrementalBuildManager()
    private val toolchain = ManifestToolchain("2.4.10", 21)

    @Test
    fun `reports stale when classes directory is missing or empty`() {
        val srcFile = tempDir.resolve("Main.kt")
        Files.writeString(srcFile, "fun main() {}")
        val scanned = listOf(ScannedFile(srcFile, "Main.kt", Files.size(srcFile), 1000L))

        val status = manager.checkUpToDate(
            projectDir = tempDir,
            scope = BuildScope.MAIN,
            sources = scanned,
            resources = emptyList(),
            classpath = emptyList(),
            toolchain = toolchain,
        )

        assertFalse(status.isUpToDate)
        assertTrue((status as IncrementalStatus.Stale).reason.contains("missing or empty"))
    }

    @Test
    fun `reports up to date when fingerprint matches and classes exist`() {
        val classesDir = tempDir.resolve("build/classes/kotlin/main")
        Files.createDirectories(classesDir)
        Files.writeString(classesDir.resolve("MainKt.class"), "mock-bytecode")

        val srcFile = tempDir.resolve("Main.kt")
        Files.writeString(srcFile, "fun main() {}")
        val scanned = listOf(ScannedFile(srcFile, "Main.kt", Files.size(srcFile), 1000L))

        // Record build
        manager.recordBuild(
            projectDir = tempDir,
            scope = BuildScope.MAIN,
            sources = scanned,
            resources = emptyList(),
            classpath = emptyList(),
            toolchain = toolchain,
        )

        // Check immediately
        val status = manager.checkUpToDate(
            projectDir = tempDir,
            scope = BuildScope.MAIN,
            sources = scanned,
            resources = emptyList(),
            classpath = emptyList(),
            toolchain = toolchain,
        )

        assertTrue(status.isUpToDate)
    }

    @Test
    fun `detects when source file changes`() {
        val classesDir = tempDir.resolve("build/classes/kotlin/main")
        Files.createDirectories(classesDir)
        Files.writeString(classesDir.resolve("MainKt.class"), "mock-bytecode")

        val srcFile = tempDir.resolve("Main.kt")
        Files.writeString(srcFile, "fun main() {}")
        val scanned1 = listOf(ScannedFile(srcFile, "Main.kt", Files.size(srcFile), 1000L))

        manager.recordBuild(
            projectDir = tempDir,
            scope = BuildScope.MAIN,
            sources = scanned1,
            resources = emptyList(),
            classpath = emptyList(),
            toolchain = toolchain,
        )

        // Modify source
        Files.writeString(srcFile, "fun main() { println(1) }")
        val scanned2 = listOf(ScannedFile(srcFile, "Main.kt", Files.size(srcFile), 2000L))

        val status = manager.checkUpToDate(
            projectDir = tempDir,
            scope = BuildScope.MAIN,
            sources = scanned2,
            resources = emptyList(),
            classpath = emptyList(),
            toolchain = toolchain,
        )

        assertFalse(status.isUpToDate)
    }

    @Test
    fun `detects when toolchain changes`() {
        val classesDir = tempDir.resolve("build/classes/kotlin/main")
        Files.createDirectories(classesDir)
        Files.writeString(classesDir.resolve("MainKt.class"), "mock-bytecode")

        val srcFile = tempDir.resolve("Main.kt")
        Files.writeString(srcFile, "fun main() {}")
        val scanned = listOf(ScannedFile(srcFile, "Main.kt", Files.size(srcFile), 1000L))

        manager.recordBuild(
            projectDir = tempDir,
            scope = BuildScope.MAIN,
            sources = scanned,
            resources = emptyList(),
            classpath = emptyList(),
            toolchain = toolchain,
        )

        val updatedToolchain = ManifestToolchain("2.4.20", 21)
        val status = manager.checkUpToDate(
            projectDir = tempDir,
            scope = BuildScope.MAIN,
            sources = scanned,
            resources = emptyList(),
            classpath = emptyList(),
            toolchain = updatedToolchain,
        )

        assertFalse(status.isUpToDate)
    }

    @Test
    fun `reports stale and invalidates fingerprint when environment classes in qutivex are missing`() {
        val classesDir = tempDir.resolve("build/classes/kotlin/main")
        Files.createDirectories(classesDir)
        Files.writeString(classesDir.resolve("MainKt.class"), "mock-bytecode")

        // Create .qutivex structure with empty classes/main
        val qutivexDir = tempDir.resolve(".qutivex")
        Files.createDirectories(qutivexDir.resolve("classes/main"))

        val srcFile = tempDir.resolve("Main.kt")
        Files.writeString(srcFile, "fun main() {}")
        val scanned = listOf(ScannedFile(srcFile, "Main.kt", Files.size(srcFile), 1000L))

        manager.recordBuild(
            projectDir = tempDir,
            scope = BuildScope.MAIN,
            sources = scanned,
            resources = emptyList(),
            classpath = emptyList(),
            toolchain = toolchain,
        )

        val fpFile = tempDir.resolve("build/.qutivex-main-fingerprint")
        assertTrue(Files.exists(fpFile))

        val status = manager.checkUpToDate(
            projectDir = tempDir,
            scope = BuildScope.MAIN,
            sources = scanned,
            resources = emptyList(),
            classpath = emptyList(),
            toolchain = toolchain,
        )

        assertFalse(status.isUpToDate)
        assertTrue((status as IncrementalStatus.Stale).reason.contains("Environment classes missing or empty in .qutivex"))
        // Fingerprint must have been invalidated (deleted)
        assertFalse(Files.exists(fpFile))
    }
}
