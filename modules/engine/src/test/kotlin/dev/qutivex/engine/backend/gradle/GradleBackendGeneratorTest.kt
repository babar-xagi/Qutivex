package dev.qutivex.engine.backend.gradle

import dev.qutivex.core.manifest.ManifestApplication
import dev.qutivex.core.manifest.ManifestProject
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.manifest.ManifestToolchain
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleBackendGeneratorTest {
    @TempDir
    lateinit var tempDir: Path

    private val generator = GradleBackendGenerator()

    @Test
    fun `generates settings, build script, and wrapper files`() {
        val manifest = ManifestSpec(
            schemaVersion = 1,
            project = ManifestProject("my-app", "1.0.0"),
            toolchain = ManifestToolchain("2.4.10", 21),
            application = ManifestApplication("com.example.AppKt"),
            dependencies = mapOf("org.jetbrains.kotlinx:kotlinx-coroutines-core" to "1.10.2"),
            testDependencies = mapOf("org.junit.jupiter:junit-jupiter" to "5.10.1"),
        )

        val backendDir = generator.generate(tempDir, manifest)

        assertEquals(tempDir.resolve(".qutivex/gradle").toAbsolutePath().normalize(), backendDir)

        val settings = Files.readString(backendDir.resolve("settings.gradle.kts"))
        assertTrue(settings.contains("rootProject.name = \"my-app\""))

        val build = Files.readString(backendDir.resolve("build.gradle.kts"))
        assertTrue(build.contains("kotlin(\"jvm\") version \"2.4.10\""))
        assertTrue(build.contains("jvmToolchain(21)"))
        assertTrue(build.contains("mainClass.set(\"com.example.AppKt\")"))
        assertTrue(build.contains("implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2\")"))
        assertTrue(build.contains("testImplementation(\"org.junit.jupiter:junit-jupiter:5.10.1\")"))
        assertTrue(build.contains("layout.buildDirectory.set(projectRoot.resolve(\"build\"))"))

        assertTrue(Files.isRegularFile(backendDir.resolve("gradlew")))
        assertTrue(Files.isRegularFile(backendDir.resolve("gradlew.bat")))
        assertTrue(Files.isRegularFile(backendDir.resolve("gradle/wrapper/gradle-wrapper.jar")))
        assertTrue(Files.isRegularFile(backendDir.resolve("gradle/wrapper/gradle-wrapper.properties")))
        val wrapperProps = Files.readString(backendDir.resolve("gradle/wrapper/gradle-wrapper.properties"))
        assertTrue(wrapperProps.contains("gradle-9.5.0-bin.zip"))
    }

    @Test
    fun `escapes kotlin string characters correctly`() {
        val input = "foo\\bar\"quote\$dollar\nnewline\ttab"
        val escaped = GradleBackendGenerator.escapeKotlin(input)
        assertEquals("foo\\\\bar\\\"quote\\\$dollar\\nnewline\\ttab", escaped)
    }
}
