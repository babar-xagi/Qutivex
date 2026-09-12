package dev.qutivex.engine.build

import dev.qutivex.core.lockfile.LockfileSpec
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.dependency.ArtifactCache
import dev.qutivex.engine.dependency.DependencyManager
import dev.qutivex.engine.dependency.LocalArtifactCache
import dev.qutivex.engine.lockfile.LockfileManager
import dev.qutivex.engine.manifest.ManifestParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.isDirectory

/**
 * The core native Kotlin/JVM build engine.
 * Completely eliminates Gradle from build, run, and test lifecycles.
 */
class NativeBuildEngine(
    private val manifestParser: ManifestParser = ManifestParser(),
    private val lockfileManager: LockfileManager = LockfileManager(),
    private val sourceScanner: SourceScanner = SourceScanner(),
    private val artifactCache: ArtifactCache = LocalArtifactCache(),
    private val classpathBuilder: ClasspathBuilder = ClasspathBuilder(artifactCache),
    private val compilerRunner: KotlinCompilerRunner = KotlinCompilerRunner(),
    private val incrementalBuildManager: IncrementalBuildManager = IncrementalBuildManager(),
    private val jarPackager: JarPackager = JarPackager(),
    private val testRunner: NativeTestRunner = NativeTestRunner(),
    private val dependencyManager: DependencyManager = DependencyManager(artifactCache = artifactCache),
) {

    fun build(
        projectDir: Path,
        stdout: PrintWriter,
        stderr: PrintWriter,
        verbose: Boolean = false,
    ): Int {
        val manifest = prepareProject(projectDir)
        val lockfile = loadOrResolveLockfile(projectDir, manifest)
        val scan = sourceScanner.scan(projectDir)

        // 1. Compile Main
        val mainClassesDir = projectDir.resolve("build/classes/kotlin/main")
        val mainResourcesDir = projectDir.resolve("build/resources/main")
        val compileCp = classpathBuilder.buildCompileClasspath(projectDir, manifest, lockfile)

        if (scan.hasMainSources) {
            val mainStatus = incrementalBuildManager.checkUpToDate(
                projectDir = projectDir,
                scope = BuildScope.MAIN,
                sources = scan.mainSources,
                resources = scan.mainResources,
                classpath = compileCp,
                toolchain = manifest.toolchain,
            )

            if (!mainStatus.isUpToDate) {
                if (verbose) stdout.println("Compiling main sources...")
                cleanDirectory(mainClassesDir)
                cleanDirectory(mainResourcesDir)
                copyResources(projectDir.resolve("src/main/resources"), mainResourcesDir)

                val result = compilerRunner.compile(
                    sources = scan.mainSources,
                    classpath = compileCp,
                    outputDir = mainClassesDir,
                    toolchain = manifest.toolchain,
                    verbose = verbose,
                )

                if (!result.success) {
                    if (result.output.isNotBlank()) stderr.println(result.output)
                    return result.exitCode
                }

                incrementalBuildManager.recordBuild(
                    projectDir = projectDir,
                    scope = BuildScope.MAIN,
                    sources = scan.mainSources,
                    resources = scan.mainResources,
                    classpath = compileCp,
                    toolchain = manifest.toolchain,
                )
            } else {
                stdout.println("Main sources UP-TO-DATE")
            }
        }

        // 2. Compile & Run Tests if test sources present
        if (scan.hasTestSources) {
            val testClassesDir = projectDir.resolve("build/classes/kotlin/test")
            val testResourcesDir = projectDir.resolve("build/resources/test")
            val testCompileCp = classpathBuilder.buildTestCompileClasspath(projectDir, manifest, lockfile)

            val testStatus = incrementalBuildManager.checkUpToDate(
                projectDir = projectDir,
                scope = BuildScope.TEST,
                sources = scan.testSources,
                resources = scan.testResources,
                classpath = testCompileCp,
                toolchain = manifest.toolchain,
            )

            if (!testStatus.isUpToDate) {
                if (verbose) stdout.println("Compiling test sources...")
                cleanDirectory(testClassesDir)
                cleanDirectory(testResourcesDir)
                copyResources(projectDir.resolve("src/test/resources"), testResourcesDir)

                val result = compilerRunner.compile(
                    sources = scan.testSources,
                    classpath = testCompileCp,
                    outputDir = testClassesDir,
                    toolchain = manifest.toolchain,
                    verbose = verbose,
                )

                if (!result.success) {
                    if (result.output.isNotBlank()) stderr.println(result.output)
                    return result.exitCode
                }

                incrementalBuildManager.recordBuild(
                    projectDir = projectDir,
                    scope = BuildScope.TEST,
                    sources = scan.testSources,
                    resources = scan.testResources,
                    classpath = testCompileCp,
                    toolchain = manifest.toolchain,
                )
            } else {
                stdout.println("Test sources UP-TO-DATE")
            }

            // Run tests as part of build lifecycle
            val testRuntimeCp = classpathBuilder.buildTestRuntimeClasspath(projectDir, manifest, lockfile)
            val testExit = testRunner.execute(
                testClassesDir = testClassesDir,
                testClasspath = testRuntimeCp,
                projectDir = projectDir,
                stdout = stdout,
                stderr = stderr,
                verbose = verbose,
                manifest = manifest,
                lockfile = lockfile,
            )

            if (testExit != 0) {
                return testExit
            }
        }

        // 3. Package JAR
        val outputJar = projectDir.resolve("build/libs")
            .resolve("${manifest.project.name}-${manifest.project.version}.jar")

        val runtimeJars = classpathBuilder.buildRuntimeClasspath(projectDir, manifest, lockfile)
            .filter { Files.isRegularFile(it) }

        jarPackager.packageJar(
            classesDir = mainClassesDir,
            resourcesDir = if (Files.exists(mainResourcesDir)) mainResourcesDir else null,
            outputJar = outputJar,
            mainClass = manifest.application.mainClass.ifBlank { null },
            version = manifest.project.version,
            runtimeJars = runtimeJars,
        )

        stdout.println("✔ Packaged ${outputJar.fileName}")
        return 0
    }

    fun run(
        projectDir: Path,
        args: List<String> = emptyList(),
        stdout: PrintWriter,
        stderr: PrintWriter,
        stdin: InputStream? = null,
        verbose: Boolean = false,
    ): Int {
        val manifest = prepareProject(projectDir)
        val lockfile = loadOrResolveLockfile(projectDir, manifest)
        val scan = sourceScanner.scan(projectDir)

        // Compile main if needed
        val mainClassesDir = projectDir.resolve("build/classes/kotlin/main")
        val mainResourcesDir = projectDir.resolve("build/resources/main")
        val compileCp = classpathBuilder.buildCompileClasspath(projectDir, manifest, lockfile)

        if (scan.hasMainSources) {
            val mainStatus = incrementalBuildManager.checkUpToDate(
                projectDir = projectDir,
                scope = BuildScope.MAIN,
                sources = scan.mainSources,
                resources = scan.mainResources,
                classpath = compileCp,
                toolchain = manifest.toolchain,
            )

            if (!mainStatus.isUpToDate) {
                if (verbose) stdout.println("Compiling main sources...")
                cleanDirectory(mainClassesDir)
                cleanDirectory(mainResourcesDir)
                copyResources(projectDir.resolve("src/main/resources"), mainResourcesDir)

                val result = compilerRunner.compile(
                    sources = scan.mainSources,
                    classpath = compileCp,
                    outputDir = mainClassesDir,
                    toolchain = manifest.toolchain,
                    verbose = verbose,
                )

                if (!result.success) {
                    if (result.output.isNotBlank()) stderr.println(result.output)
                    return result.exitCode
                }

                incrementalBuildManager.recordBuild(
                    projectDir = projectDir,
                    scope = BuildScope.MAIN,
                    sources = scan.mainSources,
                    resources = scan.mainResources,
                    classpath = compileCp,
                    toolchain = manifest.toolchain,
                )
            }
        }

        val mainClass = manifest.application.mainClass
        if (mainClass.isBlank()) {
            stderr.println("Error: No 'main-class' configured in [application] section of qutivex.toml.")
            return 1
        }

        // Build runtime classpath
        val runtimeCp = classpathBuilder.buildRuntimeClasspath(projectDir, manifest, lockfile)
        val cpString = runtimeCp.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }

        val javaExecutable = resolveJavaExecutable()
        val command = mutableListOf(
            javaExecutable,
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-cp",
            cpString,
            mainClass,
        )
        command.addAll(args)

        val processBuilder = ProcessBuilder(command)
            .directory(projectDir.toFile())

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            stderr.println("Failed to start application: ${e.message}")
            return 1
        }

        // Pipe stdin if available
        if (stdin != null) {
            val stdinThread = Thread {
                try {
                    process.outputStream.use { out ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (stdin.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            out.flush()
                        }
                    }
                } catch (_: Exception) {}
            }
            stdinThread.start()
        } else {
            try { process.outputStream.close() } catch (_: Exception) {}
        }

        val outReader = BufferedReader(InputStreamReader(process.inputStream, UTF_8))
        val errReader = BufferedReader(InputStreamReader(process.errorStream, UTF_8))

        val outThread = Thread {
            outReader.useLines { lines ->
                lines.forEach { line ->
                    stdout.println(line)
                    stdout.flush()
                }
            }
        }

        val errThread = Thread {
            errReader.useLines { lines ->
                lines.forEach { line ->
                    stderr.println(line)
                    stderr.flush()
                }
            }
        }

        outThread.start()
        errThread.start()

        val exitCode = process.waitFor()
        outThread.join()
        errThread.join()

        return exitCode
    }

    fun test(
        projectDir: Path,
        stdout: PrintWriter,
        stderr: PrintWriter,
        verbose: Boolean = false,
    ): Int {
        val manifest = prepareProject(projectDir)
        val lockfile = loadOrResolveLockfile(projectDir, manifest)
        val scan = sourceScanner.scan(projectDir)

        if (!scan.hasTestSources) {
            stdout.println("No tests found.")
            return 0
        }

        // 1. Ensure main classes are compiled
        val mainClassesDir = projectDir.resolve("build/classes/kotlin/main")
        val mainResourcesDir = projectDir.resolve("build/resources/main")
        val compileCp = classpathBuilder.buildCompileClasspath(projectDir, manifest, lockfile)

        if (scan.hasMainSources) {
            val mainStatus = incrementalBuildManager.checkUpToDate(
                projectDir = projectDir,
                scope = BuildScope.MAIN,
                sources = scan.mainSources,
                resources = scan.mainResources,
                classpath = compileCp,
                toolchain = manifest.toolchain,
            )

            if (!mainStatus.isUpToDate) {
                if (verbose) stdout.println("Compiling main sources...")
                cleanDirectory(mainClassesDir)
                cleanDirectory(mainResourcesDir)
                copyResources(projectDir.resolve("src/main/resources"), mainResourcesDir)

                val result = compilerRunner.compile(
                    sources = scan.mainSources,
                    classpath = compileCp,
                    outputDir = mainClassesDir,
                    toolchain = manifest.toolchain,
                    verbose = verbose,
                )

                if (!result.success) {
                    if (result.output.isNotBlank()) stderr.println(result.output)
                    return result.exitCode
                }

                incrementalBuildManager.recordBuild(
                    projectDir = projectDir,
                    scope = BuildScope.MAIN,
                    sources = scan.mainSources,
                    resources = scan.mainResources,
                    classpath = compileCp,
                    toolchain = manifest.toolchain,
                )
            }
        }

        // 2. Compile test sources
        val testClassesDir = projectDir.resolve("build/classes/kotlin/test")
        val testResourcesDir = projectDir.resolve("build/resources/test")
        val testCompileCp = classpathBuilder.buildTestCompileClasspath(projectDir, manifest, lockfile)

        val testStatus = incrementalBuildManager.checkUpToDate(
            projectDir = projectDir,
            scope = BuildScope.TEST,
            sources = scan.testSources,
            resources = scan.testResources,
            classpath = testCompileCp,
            toolchain = manifest.toolchain,
        )

        if (!testStatus.isUpToDate) {
            if (verbose) stdout.println("Compiling test sources...")
            cleanDirectory(testClassesDir)
            cleanDirectory(testResourcesDir)
            copyResources(projectDir.resolve("src/test/resources"), testResourcesDir)

            val result = compilerRunner.compile(
                sources = scan.testSources,
                classpath = testCompileCp,
                outputDir = testClassesDir,
                toolchain = manifest.toolchain,
                verbose = verbose,
            )

            if (!result.success) {
                if (result.output.isNotBlank()) stderr.println(result.output)
                return result.exitCode
            }

            incrementalBuildManager.recordBuild(
                projectDir = projectDir,
                scope = BuildScope.TEST,
                sources = scan.testSources,
                resources = scan.testResources,
                classpath = testCompileCp,
                toolchain = manifest.toolchain,
            )
        }

        // 3. Run JUnit tests
        val testRuntimeCp = classpathBuilder.buildTestRuntimeClasspath(projectDir, manifest, lockfile)
        return testRunner.execute(
            testClassesDir = testClassesDir,
            testClasspath = testRuntimeCp,
            projectDir = projectDir,
            stdout = stdout,
            stderr = stderr,
            verbose = verbose,
            manifest = manifest,
            lockfile = lockfile,
        )
    }

    private fun prepareProject(projectDir: Path): ManifestSpec {
        val manifestFile = projectDir.resolve("qutivex.toml")
        if (!Files.exists(manifestFile)) {
            throw IllegalArgumentException(
                "No 'qutivex.toml' manifest found in '$projectDir'. Run 'qutivex init' first.",
            )
        }
        return manifestParser.parse(manifestFile)
    }

    private fun loadOrResolveLockfile(projectDir: Path, manifest: ManifestSpec): LockfileSpec? {
        val lockFile = projectDir.resolve("qutivex.lock")
        if (Files.exists(lockFile)) {
            return try {
                lockfileManager.read(projectDir)
            } catch (_: Exception) {
                null
            }
        }

        // If manifest has dependencies but no lockfile exists, resolve them automatically
        if (manifest.dependencies.isNotEmpty() || manifest.testDependencies.isNotEmpty()) {
            return try {
                val sw = java.io.StringWriter()
                val pw = PrintWriter(sw)
                dependencyManager.install(projectDir, stdout = pw, stderr = pw)
                lockfileManager.read(projectDir)
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    private fun copyResources(srcDir: Path, destDir: Path) {
        if (!Files.exists(srcDir) || !srcDir.isDirectory()) return
        Files.createDirectories(destDir)
        Files.walk(srcDir).use { stream ->
            stream.forEach { source ->
                val relative = srcDir.relativize(source)
                val destination = destDir.resolve(relative)
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination)
                } else {
                    val parent = destination.parent
                    if (parent != null) Files.createDirectories(parent)
                    Files.copy(source, destination, REPLACE_EXISTING)
                }
            }
        }
    }

    private fun resolveJavaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        if (!javaHome.isNullOrBlank()) {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val binName = if (isWindows) "java.exe" else "java"
            val javaPath = Path.of(javaHome, "bin", binName)
            if (Files.exists(javaPath)) {
                return javaPath.toAbsolutePath().toString()
            }
        }
        return "java"
    }

    private fun cleanDirectory(dir: Path) {
        if (!Files.exists(dir)) return
        try {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    if (path != dir) {
                        try { Files.delete(path) } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
