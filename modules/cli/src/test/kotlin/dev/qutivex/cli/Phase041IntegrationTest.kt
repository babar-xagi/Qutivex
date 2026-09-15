package dev.qutivex.cli

import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase041IntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val toolchainManager by lazy {
        dev.qutivex.engine.toolchain.ToolchainManager(baseDir = tempDir.resolve("toolchains"))
    }

    private val cli by lazy {
        QutivexCli(toolchainManager = toolchainManager)
    }

    @Test
    fun `toolchain cli lifecycle - install, list, use, update, remove`() {
        // 1. Toolchain list before install
        val listRes1 = execute(listOf("toolchain", "list"))
        assertEquals(0, listRes1.exitCode, listRes1.stderr)
        assertTrue(listRes1.stdout.contains("Installed Toolchains"))

        // 2. Install Kotlin toolchain
        val installKt = execute(listOf("toolchain", "install", "kotlin", "2.4.10"))
        assertEquals(0, installKt.exitCode, installKt.stderr)
        assertTrue(installKt.stdout.contains("2.4.10"))

        // 3. Install JDK toolchain
        val installJdk = execute(listOf("toolchain", "install", "jdk", "21"))
        assertEquals(0, installJdk.exitCode, installJdk.stderr)
        assertTrue(installJdk.stdout.contains("JDK toolchain 21"))

        // 4. List after install shows newly installed toolchains
        val listRes2 = execute(listOf("toolchain", "list"))
        assertEquals(0, listRes2.exitCode, listRes2.stderr)
        assertTrue(listRes2.stdout.contains("2.4.10"))
        assertTrue(listRes2.stdout.contains("21"))

        // 4b. List filtering by type
        val listKt = execute(listOf("toolchain", "list", "kotlin"))
        assertEquals(0, listKt.exitCode, listKt.stderr)
        assertTrue(listKt.stdout.contains("Kotlin:"))
        assertTrue(listKt.stdout.contains("2.4.10"))
        assertFalse(listKt.stdout.contains("JDK:"))

        val listJdk = execute(listOf("toolchain", "list", "jdk"))
        assertEquals(0, listJdk.exitCode, listJdk.stderr)
        assertTrue(listJdk.stdout.contains("JDK:"))
        assertTrue(listJdk.stdout.contains("21"))
        assertFalse(listJdk.stdout.contains("Kotlin:"))

        // 5. Toolchain use in project
        val projectDir = tempDir.resolve("use-project")
        val initRes = execute(listOf("init", projectDir.toString()))
        assertEquals(0, initRes.exitCode, initRes.stderr)

        // Cannot use nonexistent toolchain
        val useNonExistent = execute(listOf("toolchain", "use", "kotlin", "9.9.9"), projectDir)
        assertEquals(1, useNonExistent.exitCode)
        assertTrue(useNonExistent.stderr.contains("not installed"))

        // Install additional toolchains
        execute(listOf("toolchain", "install", "kotlin", "2.1.20"))
        execute(listOf("toolchain", "install", "jdk", "17"))

        val useKt = execute(listOf("toolchain", "use", "kotlin", "2.1.20"), projectDir)
        assertEquals(0, useKt.exitCode, useKt.stderr)
        assertTrue(useKt.stdout.contains("Set active kotlin toolchain to 2.1.20 in qutivex.toml"))

        val useJdk = execute(listOf("toolchain", "use", "jdk", "17"), projectDir)
        assertEquals(0, useJdk.exitCode, useJdk.stderr)
        assertTrue(useJdk.stdout.contains("Set active jdk toolchain to 17 in qutivex.toml"))

        val manifestContent = Files.readString(projectDir.resolve("qutivex.toml"))
        assertTrue(manifestContent.contains("kotlin = \"2.1.20\""))
        assertTrue(manifestContent.contains("jvm = 17"))

        // Active toolchain cannot be removed
        val removeActiveKt = execute(listOf("toolchain", "remove", "kotlin", "2.1.20"), projectDir)
        assertEquals(1, removeActiveKt.exitCode)
        assertTrue(removeActiveKt.stderr.contains("currently active"))

        val removeActiveJdk = execute(listOf("toolchain", "remove", "jdk", "17"), projectDir)
        assertEquals(1, removeActiveJdk.exitCode)
        assertTrue(removeActiveJdk.stderr.contains("currently active"))

        // 6. Toolchain update (all, kotlin, jdk)
        val updateRes = execute(listOf("toolchain", "update"))
        assertEquals(0, updateRes.exitCode, updateRes.stderr)
        assertTrue(updateRes.stdout.contains("All toolchains are up to date"))

        val updateKt = execute(listOf("toolchain", "update", "kotlin"))
        assertEquals(0, updateKt.exitCode, updateKt.stderr)
        assertTrue(updateKt.stdout.contains("installed Kotlin toolchain(s)"))

        val updateJdk = execute(listOf("toolchain", "update", "jdk"))
        assertEquals(0, updateJdk.exitCode, updateJdk.stderr)
        assertTrue(updateJdk.stdout.contains("installed JDK toolchain(s)"))

        // 7. Remove inactive toolchain
        val removeRes = execute(listOf("toolchain", "remove", "kotlin", "2.4.10"), projectDir)
        assertEquals(0, removeRes.exitCode, removeRes.stderr)
        assertTrue(removeRes.stdout.contains("Removed kotlin toolchain 2.4.10"))
    }

    @Test
    fun `project environment lifecycle - info, clean, recreate`() {
        val projectDir = tempDir.resolve("env-app")
        execute(listOf("init", projectDir.toString()))

        // 1. env info on fresh project
        val info1 = execute(listOf("env", "info"), projectDir)
        assertEquals(0, info1.exitCode, info1.stderr)
        assertTrue(info1.stdout.contains("Project Environment: env-app"))
        assertTrue(info1.stdout.contains(".qutivex"))
        assertTrue(Files.exists(projectDir.resolve(".qutivex/env/env.toml")))
        assertTrue(Files.exists(projectDir.resolve(".qutivex/state/project-state.json")))

        // 2. Build app to populate classes and state
        val buildRes = execute(listOf("build"), projectDir)
        assertEquals(0, buildRes.exitCode, buildRes.stderr)
        assertTrue(Files.exists(projectDir.resolve(".qutivex/classes/main")))
        assertTrue(Files.exists(projectDir.resolve(".qutivex/build")))

        // 3. env info after build shows populated main classes
        val info2 = execute(listOf("env", "info"), projectDir)
        assertEquals(0, info2.exitCode, info2.stderr)
        assertTrue(info2.stdout.contains("main (1 classes)"))

        // 4. env clean
        val cleanRes = execute(listOf("env", "clean"), projectDir)
        assertEquals(0, cleanRes.exitCode, cleanRes.stderr)
        assertTrue(cleanRes.stdout.contains("Cleaned project environment"))
        assertFalse(Files.exists(projectDir.resolve("build")))

        // 5. env recreate
        val recreateRes = execute(listOf("env", "recreate"), projectDir)
        assertEquals(0, recreateRes.exitCode, recreateRes.stderr)
        assertTrue(recreateRes.stdout.contains("Recreated project environment"))
        assertTrue(Files.exists(projectDir.resolve(".qutivex/env/env.toml")))

        val info3 = execute(listOf("env", "info"), projectDir)
        assertEquals(0, info3.exitCode, info3.stderr)
        assertTrue(info3.stdout.contains("State:        READY"))
        assertTrue(info3.stdout.contains("main (0 classes)"))
    }

    @Test
    fun `deleting qutivex folder invalidates build state and restores environment outputs without false UP-TO-DATE`() {
        val projectDir = tempDir.resolve("sync-app")
        execute(listOf("init", projectDir.toString()))

        // 1. Initial build compiles
        val build1 = execute(listOf("build"), projectDir)
        assertEquals(0, build1.exitCode, build1.stderr)
        assertFalse(build1.stdout.contains("Main sources UP-TO-DATE"))

        // 2. Second build is UP-TO-DATE
        val build2 = execute(listOf("build"), projectDir)
        assertEquals(0, build2.exitCode, build2.stderr)
        assertTrue(build2.stdout.contains("Main sources UP-TO-DATE"))

        // 3. Delete .qutivex directory manually
        val qutivexDir = projectDir.resolve(".qutivex")
        Files.walk(qutivexDir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        assertFalse(Files.exists(qutivexDir))

        // 4. Build must NOT say UP-TO-DATE; it must recompile and restore environment outputs
        val build3 = execute(listOf("build"), projectDir)
        assertEquals(0, build3.exitCode, build3.stderr)
        assertFalse(build3.stdout.contains("Main sources UP-TO-DATE"), "Must never claim UP-TO-DATE when .qutivex outputs are missing")

        // 5. env info reports matching class count, not 0 classes
        val infoRes = execute(listOf("env", "info"), projectDir)
        assertEquals(0, infoRes.exitCode, infoRes.stderr)
        assertTrue(infoRes.stdout.contains("main (1 classes)"), "Environment class count must match compiled outputs: ${infoRes.stdout}")
    }

    @Test
    fun `automatic project environment isolation between two distinct projects`() {
        val projectA = tempDir.resolve("project-a")
        val projectB = tempDir.resolve("project-b")

        execute(listOf("init", projectA.toString()))
        execute(listOf("init", projectB.toString()))

        // Project A: modify Main to output specific greeting
        val mainA = projectA.resolve("src/main/kotlin/Main.kt")
        Files.writeString(mainA, "fun main() { println(\"FROM_PROJECT_A\") }\n")

        // Project B: modify Main to output different greeting and configure Kotlin 2.1.20
        val mainB = projectB.resolve("src/main/kotlin/Main.kt")
        Files.writeString(mainB, "fun main() { println(\"FROM_PROJECT_B\") }\n")
        execute(listOf("toolchain", "install", "kotlin", "2.1.20"))
        execute(listOf("toolchain", "use", "kotlin", "2.1.20"), projectB)

        // Run Project A
        val runA = execute(listOf("run"), projectA)
        assertEquals(0, runA.exitCode, runA.stderr)
        assertTrue(runA.stdout.contains("FROM_PROJECT_A"))
        assertFalse(runA.stdout.contains("FROM_PROJECT_B"))

        // Run Project B
        val runB = execute(listOf("run"), projectB)
        assertEquals(0, runB.exitCode, runB.stderr)
        assertTrue(runB.stdout.contains("FROM_PROJECT_B"))
        assertFalse(runB.stdout.contains("FROM_PROJECT_A"))

        // Verify independent .qutivex environments
        val envA = Files.readString(projectA.resolve(".qutivex/env/env.toml"))
        val envB = Files.readString(projectB.resolve(".qutivex/env/env.toml"))

        assertTrue(envA.contains("project = \"project-a\""))
        assertTrue(envB.contains("project = \"project-b\""))
        assertTrue(envB.contains("kotlin = \"2.1.20\""))
    }

    @Test
    fun `doctor command inspects toolchains and active project environment`() {
        val projectDir = tempDir.resolve("doctor-app")
        execute(listOf("init", projectDir.toString()))
        execute(listOf("env", "info"), projectDir)

        val docRes = execute(listOf("doctor"), projectDir)
        assertEquals(0, docRes.exitCode, docRes.stderr)
        assertTrue(docRes.stdout.contains("Qutivex Environment"))
        assertTrue(docRes.stdout.contains("Toolchains"))
        assertTrue(docRes.stdout.contains("Environment"))
        assertTrue(docRes.stdout.contains("isolated (.qutivex)"))
        assertTrue(docRes.stdout.contains("Build Engine"))
        assertTrue(docRes.stdout.contains("native"))
        assertFalse(docRes.stdout.contains("Gradle"))
    }

    @Test
    fun `toolchain error handling and recovery messages`() {
        // Unknown subcommand
        val err1 = execute(listOf("toolchain", "badcmd"))
        assertEquals(2, err1.exitCode)
        assertTrue(err1.stderr.contains("Unknown subcommand for 'toolchain'"))

        // Missing arguments
        val err2 = execute(listOf("toolchain", "install", "kotlin"))
        assertEquals(2, err2.exitCode)
        assertTrue(err2.stderr.contains("Missing arguments"))

        // Removing uninstalled toolchain
        val err3 = execute(listOf("toolchain", "remove", "kotlin", "99.99.99"))
        assertEquals(1, err3.exitCode)
        assertTrue(err3.stderr.contains("not installed"))

        // Invalid toolchain type for list
        val errList = execute(listOf("toolchain", "list", "invalid_type"))
        assertEquals(2, errList.exitCode)
        assertTrue(errList.stderr.contains("Unknown toolchain type 'invalid_type'"))

        // Invalid toolchain type for update
        val errUpdate = execute(listOf("toolchain", "update", "invalid_type"))
        assertEquals(2, errUpdate.exitCode)
        assertTrue(errUpdate.stderr.contains("Unknown toolchain type 'invalid_type'"))

        // env in non-project directory
        val nonProject = tempDir.resolve("empty-dir")
        Files.createDirectories(nonProject)
        val err4 = execute(listOf("env", "info"), nonProject)
        assertEquals(1, err4.exitCode)
        assertTrue(err4.stderr.contains("No 'qutivex.toml' manifest found"))
    }

    private fun execute(args: List<String>, workingDirectory: Path = tempDir): Result {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val exitCode = cli.execute(args, workingDirectory, PrintWriter(stdout), PrintWriter(stderr))
        return Result(exitCode, stdout.toString(), stderr.toString())
    }

    private data class Result(val exitCode: Int, val stdout: String, val stderr: String)
}
