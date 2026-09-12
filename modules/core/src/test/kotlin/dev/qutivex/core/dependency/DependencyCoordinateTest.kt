package dev.qutivex.core.dependency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class DependencyCoordinateTest {

    @Test
    fun `parse parses colon-separated coordinates correctly`() {
        val coord = DependencyCoordinate.parse("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
        assertEquals("org.jetbrains.kotlinx", coord.group)
        assertEquals("kotlinx-coroutines-core", coord.artifact)
        assertEquals("1.8.0", coord.version)
        assertEquals("org.jetbrains.kotlinx:kotlinx-coroutines-core", coord.key)
        assertEquals("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0", coord.standardNotation)
    }

    @Test
    fun `parse parses at-separated version coordinates correctly`() {
        val coord = DependencyCoordinate.parse("io.ktor:ktor-client-core@3.0.0")
        assertEquals("io.ktor", coord.group)
        assertEquals("ktor-client-core", coord.artifact)
        assertEquals("3.0.0", coord.version)
        assertEquals("io.ktor:ktor-client-core", coord.key)
        assertEquals("io.ktor:ktor-client-core:3.0.0", coord.standardNotation)
    }

    @Test
    fun `parseKey accepts group and artifact`() {
        val key = DependencyCoordinate.parseKey("org.jetbrains.kotlinx:kotlinx-coroutines-core")
        assertEquals("org.jetbrains.kotlinx:kotlinx-coroutines-core", key)
    }

    @Test
    fun `parseKey accepts group, artifact, and version`() {
        val key1 = DependencyCoordinate.parseKey("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
        assertEquals("org.jetbrains.kotlinx:kotlinx-coroutines-core", key1)

        val key2 = DependencyCoordinate.parseKey("io.ktor:ktor-client-core@3.0.0")
        assertEquals("io.ktor:ktor-client-core", key2)
    }

    @Test
    fun `parse rejects coordinates with missing version`() {
        assertThrows<IllegalArgumentException> {
            DependencyCoordinate.parse("org.jetbrains.kotlinx:kotlinx-coroutines-core")
        }
    }

    @Test
    fun `parse rejects empty or malformed coordinates`() {
        assertThrows<IllegalArgumentException> { DependencyCoordinate.parse("") }
        assertThrows<IllegalArgumentException> { DependencyCoordinate.parse("   ") }
        assertThrows<IllegalArgumentException> { DependencyCoordinate.parse("invalid") }
        assertThrows<IllegalArgumentException> { DependencyCoordinate.parse("a:b:c:d") }
        assertThrows<IllegalArgumentException> { DependencyCoordinate.parse(":artifact:1.0") }
        assertThrows<IllegalArgumentException> { DependencyCoordinate.parse("group::1.0") }
        assertThrows<IllegalArgumentException> { DependencyCoordinate.parse("group:artifact:") }
    }

    @Test
    fun `parse rejects invalid characters`() {
        assertThrows<IllegalArgumentException> {
            DependencyCoordinate.parse("group name:artifact:1.0")
        }
        assertThrows<IllegalArgumentException> {
            DependencyCoordinate.parse("group:artifact:1.0?")
        }
    }
}
