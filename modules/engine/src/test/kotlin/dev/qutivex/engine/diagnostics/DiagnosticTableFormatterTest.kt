package dev.qutivex.engine.diagnostics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticTableFormatterTest {

    private val formatter = DiagnosticTableFormatter()

    @Test
    fun `format produces clean aligned table with standard rows`() {
        val rows = listOf(
            DiagnosticRow("Qutivex", "0.2.0-dev", "OK"),
            DiagnosticRow("Platform", "Windows 10 amd64", "OK"),
            DiagnosticRow("Java", "21.0.12.1", "OK"),
            DiagnosticRow("JAVA_HOME", "detected", "OK"),
            DiagnosticRow("Gradle", "managed", "OK"),
            DiagnosticRow("Repository", "reachable", "OK"),
        )

        val output = formatter.format(rows)
        val lines = output.trimEnd().lines()

        assertEquals(6, lines.size)
        // Check alignment: all status indicators "OK" must start at the exact same column index
        val okIndices = lines.map { it.indexOf("OK") }
        val firstIndex = okIndices.first()
        assertTrue(firstIndex > 0)
        assertTrue(okIndices.all { it == firstIndex }, "All OK statuses should align at index $firstIndex: $okIndices")

        // Check specific lines
        assertEquals("Qutivex     0.2.0-dev          OK", lines[0])
        assertEquals("Platform    Windows 10 amd64   OK", lines[1])
        assertEquals("Java        21.0.12.1          OK", lines[2])
        assertEquals("JAVA_HOME   detected           OK", lines[3])
        assertEquals("Gradle      managed            OK", lines[4])
        assertEquals("Repository  reachable          OK", lines[5])
    }

    @Test
    fun `format expands columns dynamically for long values without colliding`() {
        val rows = listOf(
            DiagnosticRow("Qutivex", "0.2.0-dev", "OK"),
            DiagnosticRow("Platform", "Microsoft Windows 11 Enterprise Edition 64-bit", "OK"),
            DiagnosticRow("Java", "21.0.12.1-temurin", "OK"),
        )

        val output = formatter.format(rows)
        val lines = output.trimEnd().lines()

        val okIndices = lines.map { it.indexOf("OK") }
        val firstIndex = okIndices.first()
        assertTrue(okIndices.all { it == firstIndex }, "All OK statuses should align at index $firstIndex")
        assertTrue(firstIndex >= "Platform    Microsoft Windows 11 Enterprise Edition 64-bit".length)
    }
}
