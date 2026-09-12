package dev.qutivex.engine.build

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/**
 * Executes JUnit Platform tests natively by spawning an isolated JVM test worker process.
 */
class NativeTestRunner {

    fun execute(
        testClassesDir: Path,
        testClasspath: List<Path>,
        projectDir: Path,
        stdout: PrintWriter,
        stderr: PrintWriter,
        verbose: Boolean = false,
    ): Int {
        if (!Files.exists(testClassesDir)) {
            stdout.println("No test classes found in $testClassesDir")
            return 0
        }

        // 1. Build complete classpath including QutivexTestWorker
        val fullClasspath = LinkedHashSet<Path>()
        fullClasspath.add(testClassesDir)
        fullClasspath.addAll(testClasspath)

        // Include the jar/dir containing QutivexTestWorker
        ClasspathBuilder.findJarForClass(QutivexTestWorker::class.java)?.let {
            fullClasspath.add(it)
        }

        val cpString = fullClasspath
            .filter { Files.exists(it) }
            .joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }

        // 2. Locate Java executable
        val javaExecutable = resolveJavaExecutable()

        val command = mutableListOf(
            javaExecutable,
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-cp",
            cpString,
            "dev.qutivex.engine.build.QutivexTestWorker",
            testClassesDir.toAbsolutePath().toString(),
        )

        val processBuilder = ProcessBuilder(command)
            .directory(projectDir.toFile())

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            stderr.println("Failed to start test worker process: ${e.message}")
            return 1
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
}
