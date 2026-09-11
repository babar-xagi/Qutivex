package dev.qutivex.engine.diagnostics

import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentDiagnosticsTest {
    private val diagnostics = EnvironmentDiagnostics()

    @Test
    fun `inspects environment and reports status`() {
        val result = diagnostics.inspect("0.1.0")
        assertEquals("0.1.0", result.qutivexVersion)
        assertEquals("managed", result.gradleStatus)
        assertTrue(result.platform.isNotBlank())

        val stdout = StringWriter()
        val stderr = StringWriter()
        val exitCode = diagnostics.printReport(result, PrintWriter(stdout), PrintWriter(stderr))

        val output = stdout.toString()
        assertTrue(output.contains("Qutivex Environment"))
        assertTrue(output.contains("Qutivex"))
        assertTrue(output.contains("Platform"))
        assertTrue(output.contains("Java"))
        assertTrue(output.contains("Gradle"))

        // Since we are running under JDK 21 in test, javaOk should be true
        if (result.javaMajorVersion == 21) {
            assertEquals(0, exitCode)
            assertTrue(result.javaOk)
        }
    }
}
