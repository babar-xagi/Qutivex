package dev.qutivex.engine.project

import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectInitializerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val initializer = ProjectInitializer()

    @Test
    fun `creates a scaffold with a normalized project name and manifest`() {
        val target = temporaryDirectory.resolve("My App")

        val project = initializer.initialize(target)

        assertEquals("my-app", project.name)
        val manifest = Files.readString(target.resolve("qutivex.toml"))
        assertTrue(manifest.startsWith("schema-version = 1\n"))
        assertTrue(manifest.contains("[project]\nname = \"my-app\"\nversion = \"0.1.0\""))
        assertTrue(manifest.contains("[toolchain]\nkotlin = \"2.4.10\"\njvm = 21"))
        assertTrue(manifest.contains("[application]\nmain-class = \"MainKt\""))
        assertTrue(manifest.contains("[dependencies]\n"))
        assertTrue(manifest.contains("[test-dependencies]\n"))
        assertTrue(Files.readString(target.resolve("src/main/kotlin/Main.kt")).contains("Hello from my-app!"))
        assertTrue(Files.isDirectory(target.resolve("src/test/kotlin")))
        assertTrue(Files.readString(target.resolve(".gitignore")).contains(".qutivex/"))
        assertTrue(Files.readString(target.resolve("README.md")).contains("not\nimplemented yet"))
        assertFalse(Files.exists(target.resolve("build.gradle.kts")))
    }

    @Test
    fun `initializes an existing empty directory`() {
        val target = Files.createDirectory(temporaryDirectory.resolve("empty-app"))

        initializer.initialize(target)

        assertTrue(Files.isRegularFile(target.resolve("qutivex.toml")))
    }

    @Test
    fun `refuses a nonempty directory and preserves its contents`() {
        val target = Files.createDirectory(temporaryDirectory.resolve("existing-app"))
        val existing = target.resolve("qutivex.toml")
        Files.writeString(existing, "User-owned manifest\n")

        assertFailsWith<FileAlreadyExistsException> { initializer.initialize(target) }

        assertEquals("User-owned manifest\n", Files.readString(existing))
        Files.list(target).use { assertEquals(1L, it.count()) }
    }

    @Test
    fun `refuses a nonempty directory even if it only contains a hidden file`() {
        val target = Files.createDirectory(temporaryDirectory.resolve("existing-app"))
        Files.writeString(target.resolve(".gitignore"), "existing content")

        assertFailsWith<FileAlreadyExistsException> { initializer.initialize(target) }

        assertEquals("existing content", Files.readString(target.resolve(".gitignore")))
        assertFalse(Files.exists(target.resolve("qutivex.toml")))
    }

    @Test
    fun `refuses an existing file without changing it`() {
        val target = temporaryDirectory.resolve("existing-file")
        Files.writeString(target, "Keep this file")

        assertFailsWith<FileAlreadyExistsException> { initializer.initialize(target) }

        assertEquals("Keep this file", Files.readString(target))
    }

    @Test
    fun `rejects an invalid name before creating the target`() {
        val target = temporaryDirectory.resolve("123-invalid")

        assertFailsWith<IllegalArgumentException> { initializer.initialize(target) }

        assertFalse(Files.exists(target))
    }
}
