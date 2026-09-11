package dev.qutivex.engine.backend.gradle

import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Executes tasks against the generated Gradle backend. */
class GradleProcessRunner(
    private val javaHome: Path? = null,
) : BackendProcessRunner {
    override fun execute(
        projectDir: Path,
        tasks: List<String>,
        extraArgs: List<String>,
        stdout: PrintWriter,
        stderr: PrintWriter,
        stdin: InputStream?,
    ): Int {
        val gradleDir = projectDir.resolve(".qutivex/gradle").toAbsolutePath().normalize()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val launcher = if (isWindows) {
            gradleDir.resolve("gradlew.bat")
        } else {
            gradleDir.resolve("gradlew")
        }

        if (!Files.exists(launcher)) {
            stderr.println("error: Backend launcher not found at '$launcher'.")
            return 1
        }

        val command = mutableListOf<String>()
        if (isWindows) {
            command.add("cmd.exe")
            command.add("/c")
            command.add(launcher.toString())
        } else {
            command.add(launcher.toString())
        }

        command.addAll(tasks)
        command.addAll(extraArgs)

        val processBuilder = ProcessBuilder(command)
            .directory(gradleDir.toFile())

        val resolvedJava = resolveJavaHome()
        if (resolvedJava != null) {
            processBuilder.environment()["JAVA_HOME"] = resolvedJava.toString()
        }

        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            stderr.println("error: Failed to launch backend process: ${e.message}")
            return 1
        }

        val executor = Executors.newCachedThreadPool()
        val stdoutFuture = executor.submit {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    stdout.println(line)
                    stdout.flush()
                }
            }
        }
        val stderrFuture = executor.submit {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    stderr.println(line)
                    stderr.flush()
                }
            }
        }

        val exitCode = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            stderr.println("error: Operation was cancelled.")
            Thread.currentThread().interrupt()
            1
        } finally {
            try {
                stdoutFuture.get(5, TimeUnit.SECONDS)
                stderrFuture.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            executor.shutdownNow()
        }

        return exitCode
    }

    private fun resolveJavaHome(): Path? {
        if (javaHome != null) return javaHome
        val envJavaHome = System.getenv("JAVA_HOME")
        if (!envJavaHome.isNullOrBlank()) {
            val path = Path.of(envJavaHome)
            if (Files.isDirectory(path)) return path
        }
        val propJavaHome = System.getProperty("java.home")
        if (!propJavaHome.isNullOrBlank()) {
            val path = Path.of(propJavaHome)
            if (Files.isDirectory(path)) return path
        }
        return null
    }
}
