package dev.qutivex.engine.build

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JarPackagerTest {

    @TempDir
    lateinit var tempDir: Path

    private val packager = JarPackager()

    @Test
    fun `packages classes and resources into executable jar with manifest`() {
        val classesDir = tempDir.resolve("classes")
        val resourcesDir = tempDir.resolve("resources")
        val outputJar = tempDir.resolve("dist/app-1.0.0.jar")

        val pkgDir = classesDir.resolve("com/example")
        Files.createDirectories(pkgDir)
        Files.writeString(pkgDir.resolve("App.class"), "mock class bytecode")

        val resSubDir = resourcesDir.resolve("config")
        Files.createDirectories(resSubDir)
        Files.writeString(resSubDir.resolve("app.properties"), "env=prod")

        packager.packageJar(
            classesDir = classesDir,
            resourcesDir = resourcesDir,
            outputJar = outputJar,
            mainClass = "com.example.AppKt",
            version = "1.0.0",
        )

        assertTrue(Files.exists(outputJar))
        assertTrue(Files.size(outputJar) > 0)

        JarFile(outputJar.toFile()).use { jar ->
            val manifest = jar.manifest
            assertNotNull(manifest)
            assertEquals("com.example.AppKt", manifest.mainAttributes.getValue("Main-Class"))
            assertEquals("Qutivex Build Engine", manifest.mainAttributes.getValue("Created-By"))
            assertEquals("1.0.0", manifest.mainAttributes.getValue("Implementation-Version"))

            assertNotNull(jar.getJarEntry("com/example/App.class"))
            assertNotNull(jar.getJarEntry("config/app.properties"))
        }
    }
}
