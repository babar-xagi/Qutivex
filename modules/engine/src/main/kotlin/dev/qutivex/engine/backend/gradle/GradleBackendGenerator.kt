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

        // 3. gradle.properties (tuned for maximum build/run/test performance)
        val propertiesContent = """
            org.gradle.jvmargs=-Xmx1024m -XX:+UseParallelGC -Dfile.encoding=UTF-8
            org.gradle.daemon=true
            org.gradle.parallel=true
            org.gradle.caching=true
            org.gradle.vfs.watch=true
        """.trimIndent() + "\n"
        writeOrUpdate(gradleDir.resolve("gradle.properties"), propertiesContent)

        // 4. Wrapper files (only copy if missing)
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
            import java.security.MessageDigest

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

            tasks.register("qutivexResolve") {
                doLast {
                    val outputFile = file("resolved-dependencies.txt")
                    val directRuntime = setOf<String>(
                        ${manifest.dependencies.keys.joinToString(", ") { "\"${escapeKotlin(it)}\"" }}
                    )
                    val directTest = setOf<String>(
                        ${manifest.testDependencies.keys.joinToString(", ") { "\"${escapeKotlin(it)}\"" }}
                    )

                    val lines = mutableListOf<String>()

                    fun process(configName: String, scopeName: String, directSet: Set<String>) {
                        val conf = configurations.findByName(configName) ?: return
                        val resolutionResult = conf.incoming.resolutionResult
                        val unresolved = resolutionResult.allDependencies
                            .filterIsInstance<org.gradle.api.artifacts.result.UnresolvedDependencyResult>()
                        if (unresolved.isNotEmpty()) {
                            val causes = unresolved.joinToString("\n") { it.failure.message ?: "Could not resolve ${'$'}{it.attempted}" }
                            throw org.gradle.api.GradleException(causes)
                        }
                        val rootComponent = resolutionResult.root

                        val artifacts = try {
                            conf.incoming.artifacts.artifacts.associateBy {
                                val id = it.id.componentIdentifier
                                if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                                    "${'$'}{id.group}:${'$'}{id.module}:${'$'}{id.version}"
                                } else ""
                            }
                        } catch (_: Exception) {
                            emptyMap<String, org.gradle.api.artifacts.result.ResolvedArtifactResult>()
                        }

                        for (component in resolutionResult.allComponents) {
                            if (component == rootComponent) continue
                            val id = component.id
                            if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                                val group = id.group
                                val module = id.module
                                val version = id.version
                                val coordKey = "${'$'}group:${'$'}module"
                                val fullCoord = "${'$'}group:${'$'}module:${'$'}version"
                                val isDirect = coordKey in directSet

                                val childDeps = component.dependencies
                                    .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                                    .mapNotNull { dep ->
                                        val sel = dep.selected.id
                                        if (sel is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                                            "${'$'}{sel.group}:${'$'}{sel.module}"
                                        } else null
                                    }

                                var checksum = ""
                                val artifact = artifacts[fullCoord]
                                if (artifact != null && artifact.file.exists()) {
                                    try {
                                        val md = MessageDigest.getInstance("SHA-256")
                                        val bytes = artifact.file.readBytes()
                                        val digest = md.digest(bytes)
                                        checksum = "sha256:" + digest.joinToString("") { "%02x".format(it) }
                                    } catch (_: Exception) {}
                                }

                                val depsStr = childDeps.distinct().sorted().joinToString(",")
                                lines.add("${'$'}group\t${'$'}module\t${'$'}version\t${'$'}scopeName\t${'$'}isDirect\t${'$'}checksum\thttps://repo.maven.apache.org/maven2/\t${'$'}depsStr")
                            }
                        }
                    }

                    process("runtimeClasspath", "runtime", directRuntime)
                    process("testRuntimeClasspath", "test", directTest)

                    outputFile.writeText(lines.joinToString("\n") + "\n")
                }
            }
        """.trimIndent() + "\n"
    }

    private fun copyWrapperFiles(gradleDir: Path) {
        copyResourceIfNotExists("/dev/qutivex/engine/backend/gradle/gradlew", gradleDir.resolve("gradlew"), executable = true)
        copyResourceIfNotExists("/dev/qutivex/engine/backend/gradle/gradlew.bat", gradleDir.resolve("gradlew.bat"))

        val wrapperDir = gradleDir.resolve("gradle/wrapper")
        Files.createDirectories(wrapperDir)
        copyResourceIfNotExists(
            "/dev/qutivex/engine/backend/gradle/wrapper/gradle-wrapper.jar",
            wrapperDir.resolve("gradle-wrapper.jar"),
        )
        copyResourceIfNotExists(
            "/dev/qutivex/engine/backend/gradle/wrapper/gradle-wrapper.properties",
            wrapperDir.resolve("gradle-wrapper.properties"),
        )
    }

    private fun copyResourceIfNotExists(resourcePath: String, target: Path, executable: Boolean = false) {
        if (Files.exists(target) && Files.size(target) > 0) {
            return
        }
        copyResource(resourcePath, target, executable)
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
