package dev.qutivex.engine.toolchain

import dev.qutivex.core.manifest.ManifestProject
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.manifest.ManifestToolchain
import dev.qutivex.core.toolchain.ToolchainType
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolchainManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val toolchainManager by lazy {
        ToolchainManager(baseDir = tempDir.resolve("toolchains"))
    }

    @Test
    fun `install and list kotlin toolchain`() {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val installed = toolchainManager.installKotlin("2.4.10", PrintWriter(stdout), PrintWriter(stderr))

        assertEquals("2.4.10", installed.version)
        assertEquals(ToolchainType.KOTLIN, installed.type)
        assertTrue(Files.exists(installed.path))
        assertTrue(stdout.toString().contains("Installed kotlin toolchain 2.4.10"))

        val list = toolchainManager.list()
        assertEquals(1, list.kotlinToolchains.size)
        assertEquals("2.4.10", list.kotlinToolchains.first().version)
    }

    @Test
    fun `install and list jdk toolchain`() {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val installed = toolchainManager.installJdk("21", PrintWriter(stdout), PrintWriter(stderr))

        assertEquals("21", installed.version)
        assertEquals(ToolchainType.JDK, installed.type)
        assertTrue(Files.exists(installed.path) || installed.isSystem)

        val list = toolchainManager.list()
        assertTrue(list.jdkToolchains.any { it.version == "21" })
    }

    @Test
    fun `rejects invalid or blank versions`() {
        val stdout = StringWriter()
        val stderr = StringWriter()

        assertFailsWith<ToolchainException> {
            toolchainManager.installKotlin("", PrintWriter(stdout), PrintWriter(stderr))
        }

        assertFailsWith<ToolchainException> {
            toolchainManager.installJdk("invalid-ver", PrintWriter(stdout), PrintWriter(stderr))
        }
    }

    @Test
    fun `offline mode fails when toolchain not installed`() {
        val stdout = StringWriter()
        val stderr = StringWriter()

        val ex = assertFailsWith<ToolchainException> {
            toolchainManager.installKotlin("9.9.9", PrintWriter(stdout), PrintWriter(stderr), offline = true)
        }
        assertTrue(ex.message!!.contains("offline mode is active"))
    }

    @Test
    fun `offline mode succeeds when toolchain is already cached`() {
        val stdout = StringWriter()
        val stderr = StringWriter()
        toolchainManager.installKotlin("2.4.10", PrintWriter(stdout), PrintWriter(stderr), offline = false)

        val stdout2 = StringWriter()
        val installed = toolchainManager.installKotlin("2.4.10", PrintWriter(stdout2), PrintWriter(stderr), offline = true)
        assertNotNull(installed)
        assertTrue(stdout2.toString().contains("already installed"))
    }

    @Test
    fun `remove toolchain removes directory cleanly`() {
        val stdout = StringWriter()
        val stderr = StringWriter()
        toolchainManager.installKotlin("2.1.20", PrintWriter(stdout), PrintWriter(stderr))
        assertTrue(Files.exists(toolchainManager.kotlinDir.resolve("2.1.20")))

        val stdout2 = StringWriter()
        toolchainManager.remove(ToolchainType.KOTLIN, "2.1.20", PrintWriter(stdout2), PrintWriter(stderr))
        assertFalse(Files.exists(toolchainManager.kotlinDir.resolve("2.1.20")))
        assertTrue(stdout2.toString().contains("Removed kotlin toolchain 2.1.20"))
    }

    @Test
    fun `remove non-existent toolchain fails with clear error`() {
        val stdout = StringWriter()
        val stderr = StringWriter()
        val ex = assertFailsWith<ToolchainException> {
            toolchainManager.remove(ToolchainType.KOTLIN, "9.9.9", PrintWriter(stdout), PrintWriter(stderr))
        }
        assertTrue(ex.message!!.contains("not installed"))
    }

    @Test
    fun `use kotlin and jdk updates qutivex toml in project`() {
        val projectDir = tempDir.resolve("my-app")
        Files.createDirectories(projectDir)
        val manifest = ManifestSpec(
            project = ManifestProject(name = "my-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
        )
        Files.writeString(projectDir.resolve("qutivex.toml"), manifest.toToml())

        val stdout = StringWriter()
        val stderr = StringWriter()
        toolchainManager.installKotlin("2.1.20", PrintWriter(stdout), PrintWriter(stderr))
        toolchainManager.installJdk("17", PrintWriter(stdout), PrintWriter(stderr))
        toolchainManager.useKotlin("2.1.20", projectDir, PrintWriter(stdout), PrintWriter(stderr))
        toolchainManager.useJdk("17", projectDir, PrintWriter(stdout), PrintWriter(stderr))

        val updatedToml = Files.readString(projectDir.resolve("qutivex.toml"))
        assertTrue(updatedToml.contains("kotlin = \"2.1.20\""))
        assertTrue(updatedToml.contains("jvm = 17"))
    }

    @Test
    fun `resolve toolchains auto-installs missing toolchain in online mode`() {
        val manifest = ManifestSpec(
            project = ManifestProject(name = "auto-tc-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
        )
        val stdout = StringWriter()
        val resolved = toolchainManager.resolveToolchains(
            manifest = manifest,
            projectDir = tempDir,
            autoInstall = true,
            offline = false,
            stdout = PrintWriter(stdout),
        )

        assertNotNull(resolved.kotlin)
        assertNotNull(resolved.jdk)
        assertEquals("2.4.10", resolved.kotlin.version)
        assertEquals("21", resolved.jdk.version)
    }

    @Test
    fun `resolve toolchains provides clear recovery message when missing in offline mode`() {
        val manifest = ManifestSpec(
            project = ManifestProject(name = "offline-tc-app"),
            toolchain = ManifestToolchain(kotlin = "3.9.99", jvm = 99),
        )

        val ex = assertFailsWith<ToolchainException> {
            toolchainManager.resolveToolchains(
                manifest = manifest,
                projectDir = tempDir,
                autoInstall = false,
                offline = true,
            )
        }
        assertTrue(ex.message!!.contains("Missing required toolchain"))
        assertTrue(ex.message!!.contains("qutivex toolchain install"))
    }

    @Test
    fun `toolchain list filtering renders only requested type`() {
        val stdout = StringWriter()
        val stderr = StringWriter()
        toolchainManager.installKotlin("2.4.10", PrintWriter(stdout), PrintWriter(stderr))
        toolchainManager.installJdk("21", PrintWriter(stdout), PrintWriter(stderr))

        val list = toolchainManager.list()
        val kotlinOnly = list.render(ToolchainType.KOTLIN)
        assertTrue(kotlinOnly.contains("Kotlin:"))
        assertTrue(kotlinOnly.contains("2.4.10"))
        assertFalse(kotlinOnly.contains("JDK:"))

        val jdkOnly = list.render(ToolchainType.JDK)
        assertTrue(jdkOnly.contains("JDK:"))
        assertTrue(jdkOnly.contains("21"))
        assertFalse(jdkOnly.contains("Kotlin:"))

        val both = list.render(null)
        assertTrue(both.contains("Kotlin:"))
        assertTrue(both.contains("JDK:"))
    }

    @Test
    fun `toolchain update filtering checks only requested type`() {
        val stdout = StringWriter()
        val stderr = StringWriter()
        toolchainManager.installKotlin("2.4.10", PrintWriter(stdout), PrintWriter(stderr))
        toolchainManager.installJdk("21", PrintWriter(stdout), PrintWriter(stderr))

        val outKt = StringWriter()
        toolchainManager.update(ToolchainType.KOTLIN, PrintWriter(outKt), PrintWriter(stderr))
        assertTrue(outKt.toString().contains("installed Kotlin toolchain(s)"))

        val outJdk = StringWriter()
        toolchainManager.update(ToolchainType.JDK, PrintWriter(outJdk), PrintWriter(stderr))
        assertTrue(outJdk.toString().contains("installed JDK toolchain(s)"))

        val outAll = StringWriter()
        toolchainManager.update(null, PrintWriter(outAll), PrintWriter(stderr))
        assertTrue(outAll.toString().contains("installed toolchain(s)"))
    }

    @Test
    fun `cannot use nonexistent toolchain`() {
        val projectDir = tempDir.resolve("use-fail-app")
        Files.createDirectories(projectDir)
        val manifest = ManifestSpec(
            project = ManifestProject(name = "use-fail-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
        )
        Files.writeString(projectDir.resolve("qutivex.toml"), manifest.toToml())

        val stdout = StringWriter()
        val stderr = StringWriter()

        val exKt = assertFailsWith<ToolchainException> {
            toolchainManager.useKotlin("9.9.9", projectDir, PrintWriter(stdout), PrintWriter(stderr))
        }
        assertTrue(exKt.message!!.contains("not installed"))

        val exJdk = assertFailsWith<ToolchainException> {
            toolchainManager.useJdk("99", projectDir, PrintWriter(stdout), PrintWriter(stderr))
        }
        assertTrue(exJdk.message!!.contains("not installed"))
    }

    @Test
    fun `cannot remove active toolchain in project context`() {
        val projectDir = tempDir.resolve("active-remove-app")
        Files.createDirectories(projectDir)
        val manifest = ManifestSpec(
            project = ManifestProject(name = "active-remove-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
        )
        Files.writeString(projectDir.resolve("qutivex.toml"), manifest.toToml())

        val stdout = StringWriter()
        val stderr = StringWriter()
        toolchainManager.installKotlin("2.4.10", PrintWriter(stdout), PrintWriter(stderr))
        toolchainManager.installJdk("21", PrintWriter(stdout), PrintWriter(stderr))

        // Removing active Kotlin must fail
        val exKt = assertFailsWith<ToolchainException> {
            toolchainManager.remove(ToolchainType.KOTLIN, "2.4.10", PrintWriter(stdout), PrintWriter(stderr), projectDir)
        }
        assertTrue(exKt.message!!.contains("currently active in project"))

        // Removing active JDK must fail
        val exJdk = assertFailsWith<ToolchainException> {
            toolchainManager.remove(ToolchainType.JDK, "21", PrintWriter(stdout), PrintWriter(stderr), projectDir)
        }
        assertTrue(exJdk.message!!.contains("currently active in project"))
    }

    @Test
    fun `inactive toolchain can be removed cleanly`() {
        val projectDir = tempDir.resolve("inactive-remove-app")
        Files.createDirectories(projectDir)
        val manifest = ManifestSpec(
            project = ManifestProject(name = "inactive-remove-app"),
            toolchain = ManifestToolchain(kotlin = "2.4.10", jvm = 21),
        )
        Files.writeString(projectDir.resolve("qutivex.toml"), manifest.toToml())

        val stdout = StringWriter()
        val stderr = StringWriter()
        toolchainManager.installKotlin("2.1.20", PrintWriter(stdout), PrintWriter(stderr))

        // 2.1.20 is not active in project (active is 2.4.10), so removal succeeds
        toolchainManager.remove(ToolchainType.KOTLIN, "2.1.20", PrintWriter(stdout), PrintWriter(stderr), projectDir)
        assertFalse(Files.exists(toolchainManager.kotlinDir.resolve("2.1.20")))
    }
}
