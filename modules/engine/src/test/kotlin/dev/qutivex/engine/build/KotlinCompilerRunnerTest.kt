package dev.qutivex.engine.build

import dev.qutivex.core.manifest.ManifestToolchain
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinCompilerRunnerTest {

    @TempDir
    lateinit var tempDir: Path

    private val runner = KotlinCompilerRunner()
    private val toolchain = ManifestToolchain("2.4.10", 21)

    @Test
    fun `compiles valid kotlin code to classes`() {
        val srcDir = tempDir.resolve("src")
        val outDir = tempDir.resolve("out")
        Files.createDirectories(srcDir)

        val srcFile = srcDir.resolve("Hello.kt")
        Files.writeString(srcFile, """
            package com.example
            fun greeting(): String = "Hello Native Build"
        """.trimIndent())

        val scanned = listOf(ScannedFile(srcFile, "Hello.kt", Files.size(srcFile), Files.getLastModifiedTime(srcFile).toMillis()))

        val stdlibJar = ClasspathBuilder.findJarForClass(KotlinVersion::class.java)
        val classpath = listOfNotNull(stdlibJar)

        val result = runner.compile(
            sources = scanned,
            classpath = classpath,
            outputDir = outDir,
            toolchain = toolchain,
        )

        assertTrue(result.success, "Compilation should succeed: ${result.output}")
        assertEquals(0, result.exitCode)
        assertTrue(Files.exists(outDir.resolve("com/example/HelloKt.class")))
    }

    @Test
    fun `fails with actionable diagnostic on compilation error`() {
        val srcDir = tempDir.resolve("src")
        val outDir = tempDir.resolve("out")
        Files.createDirectories(srcDir)

        val srcFile = srcDir.resolve("Bad.kt")
        Files.writeString(srcFile, """
            package com.example
            val x: Int = "not-an-int"
        """.trimIndent())

        val scanned = listOf(ScannedFile(srcFile, "Bad.kt", Files.size(srcFile), Files.getLastModifiedTime(srcFile).toMillis()))
        val stdlibJar = ClasspathBuilder.findJarForClass(KotlinVersion::class.java)
        val classpath = listOfNotNull(stdlibJar)

        val result = runner.compile(
            sources = scanned,
            classpath = classpath,
            outputDir = outDir,
            toolchain = toolchain,
        )

        assertFalse(result.success)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("error:") || result.output.contains("Type mismatch") || result.output.contains("Bad.kt"))
    }
}
