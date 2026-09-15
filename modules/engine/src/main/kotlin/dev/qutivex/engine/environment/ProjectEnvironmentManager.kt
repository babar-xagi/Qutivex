package dev.qutivex.engine.environment

import dev.qutivex.core.environment.EnvironmentSpec
import dev.qutivex.core.environment.ProjectStateSpec
import dev.qutivex.core.lockfile.LockfileSpec
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.build.ClasspathBuilder
import dev.qutivex.engine.dependency.DependencyManager
import dev.qutivex.engine.lockfile.LockfileManager
import dev.qutivex.engine.manifest.ManifestParser
import dev.qutivex.engine.toolchain.ToolchainManager
import java.io.PrintWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class EnvironmentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ProjectEnvironmentInfo(
    val projectName: String,
    val projectVersion: String,
    val projectDir: Path,
    val envDir: Path,
    val kotlinVersion: String,
    val kotlinPath: String,
    val jdkVersion: Int,
    val jdkPath: String,
    val directDependencies: Int,
    val transitiveDependencies: Int,
    val classpathEntriesCount: Int,
    val mainClassesCount: Int,
    val testClassesCount: Int,
    val cacheSizeBytes: Long,
    val status: String,
    val updatedAt: String,
) {
    fun render(): String = buildString {
        append("Project Environment: $projectName ($projectVersion)\n")
        append("Location: ${envDir.toAbsolutePath().normalize()}\n\n")
        append("Toolchains:\n")
        append("  • Kotlin:       $kotlinVersion ($kotlinPath)\n")
        append("  • JDK:          $jdkVersion ($jdkPath)\n\n")
        append("Environment Status:\n")
        append("  • State:        $status\n")
        append("  • Dependencies: $directDependencies direct, $transitiveDependencies transitive\n")
        append("  • Classpath:    $classpathEntriesCount entries configured\n")
        append("  • Classes:      main ($mainClassesCount classes), test ($testClassesCount classes)\n")
        append("  • Cache Size:   ${formatSize(cacheSizeBytes)}\n")
        if (updatedAt.isNotBlank()) {
            append("  • Last Updated: $updatedAt\n")
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

class ProjectEnvironmentManager(
    private val toolchainManager: ToolchainManager = ToolchainManager(),
    private val manifestParser: ManifestParser = ManifestParser(),
    private val lockfileManager: LockfileManager = LockfileManager(),
    private val classpathBuilder: ClasspathBuilder = ClasspathBuilder(),
) {

    fun ensureEnvironment(
        projectDir: Path,
        manifest: ManifestSpec,
        lockfile: LockfileSpec? = null,
        classpath: List<Path> = emptyList(),
        compilerOptions: List<String> = emptyList(),
        status: String = "READY",
    ): Path {
        val qutivexDir = projectDir.resolve(".qutivex")
        val envDir = qutivexDir.resolve("env")
        val buildDir = qutivexDir.resolve("build")
        val classesDir = qutivexDir.resolve("classes")
        val stateDir = qutivexDir.resolve("state")
        val cacheDir = qutivexDir.resolve("cache")

        Files.createDirectories(envDir)
        Files.createDirectories(buildDir)
        Files.createDirectories(classesDir.resolve("main"))
        Files.createDirectories(classesDir.resolve("test"))
        Files.createDirectories(stateDir)
        Files.createDirectories(cacheDir)

        // Resolve toolchains for paths
        val toolchainRes = try {
            toolchainManager.resolveToolchains(manifest, projectDir, autoInstall = false)
        } catch (_: Exception) {
            null
        }

        val kotlinPath = toolchainRes?.kotlin?.path?.toString() ?: ""
        val jdkPath = toolchainRes?.jdk?.path?.toString() ?: ""

        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val actualLockfile = lockfile ?: if (Files.exists(projectDir.resolve("qutivex.lock"))) {
            try { lockfileManager.read(projectDir) } catch (_: Exception) { null }
        } else null

        val directCount = manifest.dependencies.size + manifest.testDependencies.size
        val transitiveCount = (actualLockfile?.packages?.size ?: 0) - (actualLockfile?.packages?.count { it.direct } ?: 0)

        val cpFile = envDir.resolve("classpath.txt")
        val effectiveClasspath = when {
            classpath.isNotEmpty() -> classpath
            actualLockfile != null -> {
                try {
                    classpathBuilder.buildCompileClasspath(projectDir, manifest, actualLockfile)
                } catch (_: Exception) {
                    readClasspathFile(cpFile)
                }
            }
            else -> readClasspathFile(cpFile)
        }

        val effectiveCompilerOptions = if (compilerOptions.isNotEmpty()) {
            compilerOptions
        } else {
            listOf("-jvm-target", manifest.toolchain.jvm.toString())
        }

        // 1. Write env.toml
        val envSpec = EnvironmentSpec(
            projectName = manifest.project.name,
            projectVersion = manifest.project.version,
            kotlinVersion = manifest.toolchain.kotlin,
            jvmVersion = manifest.toolchain.jvm,
            kotlinPath = kotlinPath,
            jdkPath = jdkPath,
            compilerOptions = effectiveCompilerOptions,
            classpathEntries = effectiveClasspath.map { it.toAbsolutePath().normalize().toString() },
            directDependencyCount = directCount,
            transitiveDependencyCount = maxOf(0, transitiveCount),
            status = status,
            createdAt = now,
            updatedAt = now,
        )
        Files.writeString(envDir.resolve("env.toml"), envSpec.toToml(), UTF_8)

        // 2. Write classpath.txt if classpath supplied or resolved
        if (effectiveClasspath.isNotEmpty()) {
            val cpText = effectiveClasspath.joinToString("\n") { it.toAbsolutePath().normalize().toString() }
            Files.writeString(cpFile, cpText, UTF_8)
        }

        // 3. Write project-state.json
        val manifestHash = manifest.computeHash()
        val lockHash = actualLockfile?.manifestHash ?: ""
        val stateJson = """
            {
              "projectName": "${manifest.project.name}",
              "version": "${manifest.project.version}",
              "status": "$status",
              "lockHash": "$lockHash",
              "manifestHash": "$manifestHash",
              "directDependencies": $directCount,
              "transitiveDependencies": ${maxOf(0, transitiveCount)},
              "lastUpdated": "${now}"
            }
        """.trimIndent()
        Files.writeString(stateDir.resolve("project-state.json"), stateJson, UTF_8)

        return qutivexDir
    }

    fun info(projectDir: Path): ProjectEnvironmentInfo {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw EnvironmentException("No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.")
        }
        val manifest = manifestParser.parse(manifestFile)
        val qutivexDir = projectDir.resolve(".qutivex")

        val lockFile = projectDir.resolve("qutivex.lock")
        val lockfile = if (Files.exists(lockFile)) {
            try { lockfileManager.read(projectDir) } catch (_: Exception) { null }
        } else null

        // Ensure env structure exists without wiping classpath
        ensureEnvironment(projectDir, manifest, lockfile = lockfile)

        val envDir = qutivexDir.resolve("env")
        val classesMainDir = qutivexDir.resolve("classes/main")
        val classesTestDir = qutivexDir.resolve("classes/test")
        val cacheDir = qutivexDir.resolve("cache")

        val mainClasses = countClasses(classesMainDir)
        val testClasses = countClasses(classesTestDir)
        val cacheBytes = computeDirSize(cacheDir)

        val cpFile = envDir.resolve("classpath.txt")
        val cpEntries = if (Files.exists(cpFile)) {
            Files.readAllLines(cpFile, UTF_8).filter { it.isNotBlank() }.size
        } else {
            0
        }

        val toolchains = try {
            toolchainManager.resolveToolchains(manifest, projectDir, autoInstall = false)
        } catch (_: Exception) {
            null
        }

        val directCount = manifest.dependencies.size + manifest.testDependencies.size
        val transitiveCount = (lockfile?.packages?.size ?: 0) - (lockfile?.packages?.count { it.direct } ?: 0)

        val now = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())

        return ProjectEnvironmentInfo(
            projectName = manifest.project.name,
            projectVersion = manifest.project.version,
            projectDir = projectDir,
            envDir = qutivexDir,
            kotlinVersion = manifest.toolchain.kotlin,
            kotlinPath = toolchains?.kotlin?.path?.toString() ?: "not resolved",
            jdkVersion = manifest.toolchain.jvm,
            jdkPath = toolchains?.jdk?.path?.toString() ?: "not resolved",
            directDependencies = directCount,
            transitiveDependencies = maxOf(0, transitiveCount),
            classpathEntriesCount = cpEntries,
            mainClassesCount = mainClasses,
            testClassesCount = testClasses,
            cacheSizeBytes = cacheBytes,
            status = "READY",
            updatedAt = now,
        )
    }

    fun clean(projectDir: Path, stdout: PrintWriter? = null) {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw EnvironmentException("No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.")
        }
        val qutivexDir = projectDir.resolve(".qutivex")
        if (Files.exists(qutivexDir)) {
            cleanDir(qutivexDir.resolve("classes/main"))
            cleanDir(qutivexDir.resolve("classes/test"))
            cleanDir(qutivexDir.resolve("build"))
            cleanDir(qutivexDir.resolve("cache"))

            val stateFile = qutivexDir.resolve("state/project-state.json")
            if (Files.exists(stateFile)) {
                val content = Files.readString(stateFile, UTF_8)
                val updated = content.replace(Regex("\"status\":\\s*\"[^\"]+\""), "\"status\": \"CLEANED\"")
                Files.writeString(stateFile, updated, UTF_8)
            }
        }

        // Also clean build/ directory if present
        val buildDir = projectDir.resolve("build")
        if (Files.exists(buildDir)) {
            deleteRecursively(buildDir)
        }

        stdout?.println("🧹 Cleaned project environment in ${qutivexDir.toAbsolutePath().normalize()}")
    }

    fun recreate(
        projectDir: Path,
        stdout: PrintWriter,
        stderr: PrintWriter,
        dependencyManager: DependencyManager? = null,
    ) {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw EnvironmentException("No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.")
        }
        val manifest = manifestParser.parse(manifestFile)

        // Wipe .qutivex
        val qutivexDir = projectDir.resolve(".qutivex")
        if (Files.exists(qutivexDir)) {
            deleteRecursively(qutivexDir)
        }

        // Wipe build
        val buildDir = projectDir.resolve("build")
        if (Files.exists(buildDir)) {
            deleteRecursively(buildDir)
        }

        // Re-resolve and install dependencies
        val mgr = dependencyManager ?: DependencyManager()
        mgr.install(
            projectDir = projectDir,
            frozen = false,
            offline = false,
            verbose = false,
            stdout = stdout,
            stderr = stderr,
        )

        val lockfile = if (Files.exists(projectDir.resolve("qutivex.lock"))) {
            try { lockfileManager.read(projectDir) } catch (_: Exception) { null }
        } else null

        val classpath = if (lockfile != null) {
            try {
                classpathBuilder.buildCompileClasspath(projectDir, manifest, lockfile)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        // Ensure fresh environment structure with restored classpath and READY status
        ensureEnvironment(
            projectDir = projectDir,
            manifest = manifest,
            lockfile = lockfile,
            classpath = classpath,
            compilerOptions = listOf("-jvm-target", manifest.toolchain.jvm.toString()),
            status = "READY",
        )

        stdout.println("🔄 Recreated project environment for ${manifest.project.name} (${manifest.project.version})")
    }

    private fun readClasspathFile(file: Path): List<Path> {
        if (!Files.exists(file)) return emptyList()
        return try {
            Files.readAllLines(file, UTF_8)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { Path.of(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun countClasses(dir: Path): Int {
        if (!Files.exists(dir) || !dir.isDirectory()) return 0
        return Files.walk(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".class") }.count().toInt()
        }
    }

    private fun computeDirSize(dir: Path): Long {
        if (!Files.exists(dir) || !dir.isDirectory()) return 0L
        return Files.walk(dir).use { stream ->
            stream.filter { it.isRegularFile() }.mapToLong {
                try { Files.size(it) } catch (_: Exception) { 0L }
            }.sum()
        }
    }

    private fun cleanDir(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach {
                if (it != dir) {
                    try { Files.delete(it) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach {
                try { Files.delete(it) } catch (_: Exception) {}
            }
        }
    }
}
