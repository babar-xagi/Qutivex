package dev.qutivex.core.lockfile

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockfileSpecTest {

    @Test
    fun `toToml generates valid formatted lockfile content`() {
        val spec = LockfileSpec(
            version = 1,
            manifestHash = "abcdef1234567890",
            kotlinVersion = "2.4.10",
            jvmTarget = 21,
            dependencies = mapOf("org.jetbrains.kotlinx:kotlinx-coroutines-core" to "1.8.0"),
            testDependencies = mapOf("org.junit.jupiter:junit-jupiter" to "5.10.2"),
        )

        val toml = spec.toToml()
        assertTrue(toml.contains("version = 1"))
        assertTrue(toml.contains("manifest-hash = \"abcdef1234567890\""))
        assertTrue(toml.contains("kotlin = \"2.4.10\""))
        assertTrue(toml.contains("jvm = 21"))
        assertTrue(toml.contains("\"org.jetbrains.kotlinx:kotlinx-coroutines-core\" = \"1.8.0\""))
        assertTrue(toml.contains("\"org.junit.jupiter:junit-jupiter\" = \"5.10.2\""))
    }
}
