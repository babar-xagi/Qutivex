package dev.qutivex.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase3CliTest {

    @TempDir
    lateinit var tempDir: Path

    private val cli = QutivexCli()

    @Test
    fun `help text contains tree and update commands`() {
        val res = execute(listOf("--help"))
        assertEquals(0, res.exitCode)
        assertContains(res.stdout, "tree")
        assertContains(res.stdout, "update")
    }

    @Test
    fun `tree and update help flags display options`() {
        val treeHelp = execute(listOf("tree", "--help"))
        assertEquals(0, treeHelp.exitCode)
        assertContains(treeHelp.stdout, "--scope")
        assertContains(treeHelp.stdout, "--depth")

        val updateHelp = execute(listOf("update", "--help"))
        assertEquals(0, updateHelp.exitCode)
        assertContains(updateHelp.stdout, "qutivex update <coordinate>")
    }

    @Test
    fun `end-to-end tree and update commands`() {
        val projectDir = tempDir.resolve("phase3-app")

        // 1. Initialize
        val initRes = execute(listOf("init", projectDir.toString()))
        assertEquals(0, initRes.exitCode, initRes.stderr)

        // 2. Add dependency
        val addRes = execute(listOf("add", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), projectDir)
        assertEquals(0, addRes.exitCode, addRes.stderr)

        // 3. Tree output
        val treeRes = execute(listOf("tree"), projectDir)
        assertEquals(0, treeRes.exitCode, treeRes.stderr)
        assertContains(treeRes.stdout, "phase3-app")
        assertContains(treeRes.stdout, "kotlinx-coroutines-core:1.10.2")

        // 4. Tree with scope
        val treeRuntimeRes = execute(listOf("tree", "--scope", "runtime"), projectDir)
        assertEquals(0, treeRuntimeRes.exitCode, treeRuntimeRes.stderr)
        assertContains(treeRuntimeRes.stdout, "kotlinx-coroutines-core:1.10.2")

        // 5. Tree with depth
        val treeDepthRes = execute(listOf("tree", "--depth", "1"), projectDir)
        assertEquals(0, treeDepthRes.exitCode, treeDepthRes.stderr)
        assertContains(treeDepthRes.stdout, "kotlinx-coroutines-core:1.10.2")

        // 6. Update to same version returns no changes
        val sameUpdate = execute(listOf("update", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"), projectDir)
        assertEquals(0, sameUpdate.exitCode, sameUpdate.stderr)
        assertContains(sameUpdate.stdout, "is already at version 1.10.2")

        // 7. Update unknown dependency fails cleanly
        val unknownUpdate = execute(listOf("update", "com.example:not-found:1.0.0"), projectDir)
        assertEquals(1, unknownUpdate.exitCode)
        assertContains(unknownUpdate.stderr, "is not declared in qutivex.toml")
    }

    private fun execute(args: List<String>, cwd: Path = tempDir): CommandResult {
        val out = StringWriter()
        val err = StringWriter()
        val code = cli.execute(args, cwd, PrintWriter(out), PrintWriter(err))
        return CommandResult(code, out.toString(), err.toString())
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)
}
