package dev.qutivex.core.toolchain

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolchainSpecTest {

    @Test
    fun `parses toolchain type correctly`() {
        assertEquals(ToolchainType.KOTLIN, ToolchainType.from("kotlin"))
        assertEquals(ToolchainType.KOTLIN, ToolchainType.from("KOTLIN"))
        assertEquals(ToolchainType.JDK, ToolchainType.from("jdk"))
        assertEquals(ToolchainType.JDK, ToolchainType.from("JDK"))
    }

    @Test
    fun `rejects invalid toolchain type`() {
        assertFailsWith<IllegalArgumentException> {
            ToolchainType.from("python")
        }
    }

    @Test
    fun `creates valid toolchain info`() {
        val info = ToolchainInfo(
            type = ToolchainType.KOTLIN,
            version = "2.4.10",
            path = Path.of("/tmp/toolchains/kotlin/2.4.10"),
            isManaged = true,
        )
        assertEquals("2.4.10", info.version)
        assertEquals(ToolchainType.KOTLIN, info.type)
    }
}
