package dev.qutivex.engine.backend.gradle

import dev.qutivex.core.manifest.ManifestSpec
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Generates disposable pinned Gradle backend files in `<project>/.qutivex/gradle/`. */
class GradleBackendGenerator {
    fun generate(projectDir: Path, manifest: ManifestSpec): Path {
        val gradleDir = projectDir.resolve(".qutivex/gradle").toAbsolutePath().normalize()
        Files.createDirectories(gradleDir)

        // 1. settings.gradle.kts
        val settingsContent = generateSettings(manifest)
        writeOrUpdate(gradleDir.resolve("settings.gradle.kts"), settingsContent)

        // 2. build.gradle.kts
        val buildContent = generateBuild(manifest)
        writeOrUpdate(gradleDir.resolve("build.gradle.kts"), buildContent)

        // 3. gradle.properties
        val propertiesContent = "org.gradle.jvmargs=-Xmx512m\n"
        writeOrUpdate(gradleDir.resolve("gradle.properties"), propertiesContent)

        // 4. Wrapper files
        copyWrapperFiles(gradleDir)

        return gradleDir
    }

    private fun generateSettings(manifest: ManifestSpec): String = """
        rootProject.name = "${escapeKotlin(manifest.project.name)}"
    """.trimIndent() + "\n"

    private fun generateBuild(manifest: ManifestSpec): String {
        val dependenciesBlock = buildString {
            append("    testImplementation(kotlin(\"test\"))\n")
            for ((coord, version) in manifest.dependencies) {
                append("    implementation(\"${escapeKotlin(coord)}:${escapeKotlin(version)}\")\n")
            }
            for ((coord, version) in manifest.testDependencies) {
                append("    testImplementation(\"${escapeKotlin(coord)}:${escapeKotlin(version)}\")\n")
            }
        }

        return """
            import java.nio.charset.StandardCharsets
            import java.util.Base64

            plugins {
                kotlin("jvm") version "${escapeKotlin(manifest.toolchain.kotlin)}"
                application
            }

            val projectRoot = file("../..")

            kotlin {
                jvmToolchain(${manifest.toolchain.jvm})
            }

            application {
                mainClass.set("${escapeKotlin(manifest.application.mainClass)}")
            }

            repositories {
                mavenCentral()
            }

            sourceSets {
                named("main") {
                    java.setSrcDirs(emptyList<Any>())
                    kotlin.setSrcDirs(listOf(projectRoot.resolve("src/main/kotlin")))
                    resources.setSrcDirs(listOf(projectRoot.resolve("src/main/resources")))
                }
                named("test") {
                    java.setSrcDirs(emptyList<Any>())
                    kotlin.setSrcDirs(listOf(projectRoot.resolve("src/test/kotlin")))
                    resources.setSrcDirs(listOf(projectRoot.resolve("src/test/resources")))
                }
            }

            layout.buildDirectory.set(projectRoot.resolve("build"))

            tasks.named<JavaExec>("run") {
                workingDir = projectRoot
                standardInput = System.`in`
                val argsFile = file("application-args.txt")
                if (argsFile.exists()) {
                    val lines = argsFile.readLines()
                    val decoded = lines.map {
                        String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8)
                    }
                    setArgs(decoded)
                }
            }

            tasks.test {
                useJUnitPlatform()
                testLogging {
                    events("passed", "skipped", "failed")
                }
            }

            dependencies {
            $dependenciesBlock}
        """.trimIndent() + "\n"
    }

    private fun copyWrapperFiles(gradleDir: Path) {
        copyResource("/dev/qutivex/engine/backend/gradle/gradlew", gradleDir.resolve("gradlew"), executable = true)
        copyResource("/dev/qutivex/engine/backend/gradle/gradlew.bat", gradleDir.resolve("gradlew.bat"))

        val wrapperDir = gradleDir.resolve("gradle/wrapper")
        Files.createDirectories(wrapperDir)
        copyResource(
            "/dev/qutivex/engine/backend/gradle/wrapper/gradle-wrapper.jar",
            wrapperDir.resolve("gradle-wrapper.jar"),
        )
        copyResource(
            "/dev/qutivex/engine/backend/gradle/wrapper/gradle-wrapper.properties",
            wrapperDir.resolve("gradle-wrapper.properties"),
        )
    }

    private fun copyResource(resourcePath: String, target: Path, executable: Boolean = false) {
        val stream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IOException("Backend resource not found: $resourcePath")
        stream.use { input ->
            Files.copy(input, target, REPLACE_EXISTING)
        }
        if (executable) {
            target.toFile().setExecutable(true, false)
        }
    }

    private fun writeOrUpdate(path: Path, content: String) {
        if (Files.exists(path) && Files.readString(path, UTF_8) == content) {
            return
        }
        Files.writeString(path, content, UTF_8)
    }

    companion object {
        fun escapeKotlin(value: String): String = buildString {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
