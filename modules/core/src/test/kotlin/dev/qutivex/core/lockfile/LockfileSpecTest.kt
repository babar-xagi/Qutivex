package dev.qutivex.core.lockfile

import dev.qutivex.core.dependency.ResolvedDependency
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockfileSpecTest {

    @Test
    fun `toToml generates valid formatted lockfile content with packages`() {
        val spec = LockfileSpec(
            version = 1,
            manifestHash = "abcdef1234567890",
            kotlinVersion = "2.4.10",
            jvmTarget = 21,
            packages = listOf(
                ResolvedDependency(
                    group = "org.jetbrains.kotlinx",
                    artifact = "kotlinx-coroutines-core",
                    version = "1.10.2",
                    scope = "runtime",
                    direct = true,
                    dependencies = listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"),
                    checksum = "sha256:123456",
                    repository = "https://repo.maven.apache.org/maven2/",
                ),
                ResolvedDependency(
                    group = "org.jetbrains.kotlinx",
                    artifact = "kotlinx-coroutines-core-jvm",
                    version = "1.10.2",
                    scope = "runtime",
                    direct = false,
                    dependencies = emptyList(),
                    checksum = "sha256:789012",
                    repository = "https://repo.maven.apache.org/maven2/",
                ),
            ),
        )

        val toml = spec.toToml()
        assertTrue(toml.contains("version = 1"))
        assertTrue(toml.contains("manifest-hash = \"abcdef1234567890\""))
        assertTrue(toml.contains("kotlin = \"2.4.10\""))
        assertTrue(toml.contains("jvm = 21"))
        assertTrue(toml.contains("[[package]]"))
        assertTrue(toml.contains("artifact = \"kotlinx-coroutines-core\""))
        assertTrue(toml.contains("direct = true"))
        assertTrue(toml.contains("direct = false"))
        assertTrue(toml.contains("checksum = \"sha256:123456\""))
        assertEquals("1.10.2", spec.dependencies["org.jetbrains.kotlinx:kotlinx-coroutines-core"])
    }
}
