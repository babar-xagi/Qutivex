package dev.qutivex.core.environment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentSpecTest {

    @Test
    fun `formats environment spec to toml string`() {
        val spec = EnvironmentSpec(
            projectName = "sample-app",
            projectVersion = "0.1.0",
            kotlinVersion = "2.4.10",
            jvmVersion = 21,
            kotlinPath = "/home/user/.qutivex/toolchains/kotlin/2.4.10",
            jdkPath = "/home/user/.qutivex/toolchains/jdk/21",
            status = "READY",
        )
        val toml = spec.toToml()
        assertTrue(toml.contains("project = \"sample-app\""))
        assertTrue(toml.contains("kotlin = \"2.4.10\""))
        assertTrue(toml.contains("jvm = 21"))
        assertTrue(toml.contains("status = \"READY\""))
    }
}
