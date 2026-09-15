package dev.qutivex.core.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectSpecTest {
    @Test
    fun `normalizes directory capitalization and whitespace`() {
        assertEquals("my-kotlin-app", ProjectSpec.normalizeName(" My Kotlin  App "))
    }

    @Test
    fun `normalizes canonical examples deterministically`() {
        assertEquals("hello-world", ProjectSpec.normalizeName("Hello World"))
        assertEquals("uppercase", ProjectSpec.normalizeName("UPPERCASE"))
        assertEquals("bad-name", ProjectSpec.normalizeName("bad_name"))
        assertEquals("hello-123", ProjectSpec.normalizeName("hello-123"))
        assertEquals("my-app", ProjectSpec.normalizeName("  MY__APP  "))
        assertEquals("complex-proj-name", ProjectSpec.normalizeName("complex   --__proj___name"))
    }

    @Test
    fun `normalizeName rejects names that cannot produce a valid project name`() {
        val unnormalizable = listOf(
            "",
            "   ",
            "***",
            "___",
            "---",
            "2start",
            "-start",
            "start-",
            "_start",
            "start_",
            "__MY__APP__",
            "app.name",
            "../app",
            "éclair",
            "a".repeat(65),
        )

        for (input in unnormalizable) {
            assertFailsWith<IllegalArgumentException>("Expected '$input' to fail normalization") {
                ProjectSpec.normalizeName(input)
            }
        }
    }

    @Test
    fun `accepts safe names and supplies toolchain defaults`() {
        val project = ProjectSpec("my-app-2")

        assertEquals("my-app-2", project.name)
        assertEquals("2.4.10", project.kotlinVersion)
        assertEquals(21, project.jvmVersion)
        assertEquals("0.1.0", project.version)
        assertEquals("MainKt", project.mainClass)
        assertEquals("a".repeat(64), ProjectSpec("a".repeat(64)).name)
    }

    @Test
    fun `rejects names that are unsafe or outside the format`() {
        val invalidNames = listOf(
            "", "Uppercase", "2start", "-start", "start-", "my_app", "has space", "app.name", "../app", "éclair", "a".repeat(65),
        )

        for (name in invalidNames) {
            assertFailsWith<IllegalArgumentException>("Expected '$name' to be rejected") {
                ProjectSpec(name)
            }
        }
    }

    @Test
    fun `rejects reserved Windows device names on every platform`() {
        val deviceNames = listOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

        for (name in deviceNames) {
            assertFailsWith<IllegalArgumentException>("Expected reserved name '$name' to be rejected") {
                ProjectSpec(name)
            }
        }
    }
}
