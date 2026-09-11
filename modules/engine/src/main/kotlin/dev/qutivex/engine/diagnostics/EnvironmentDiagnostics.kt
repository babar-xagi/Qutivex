package dev.qutivex.engine.diagnostics

import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

data class DiagnosticResult(
    val qutivexVersion: String,
    val platform: String,
    val javaVersion: String?,
    val javaMajorVersion: Int?,
    val javaHome: String?,
    val javaPath: Path?,
    val javaOk: Boolean,
    val gradleStatus: String,
    val repositoryReachable: Boolean,
) {
    val isHealthy: Boolean get() = javaOk
}

class EnvironmentDiagnostics {
    fun inspect(version: String): DiagnosticResult {
        val osName = System.getProperty("os.name") ?: "Unknown OS"
        val osArch = System.getProperty("os.arch") ?: "x64"
        val platformStr = "$osName $osArch"

        val (javaPath, javaHome) = findJava()
        val (javaVersion, javaMajor) = getJavaVersion(javaPath)
        val javaOk = javaMajor == 21

        val repoReachable = isRepositoryReachable()

        return DiagnosticResult(
            qutivexVersion = version,
            platform = platformStr,
            javaVersion = javaVersion,
            javaMajorVersion = javaMajor,
            javaHome = javaHome,
            javaPath = javaPath,
            javaOk = javaOk,
            gradleStatus = "managed",
            repositoryReachable = repoReachable,
        )
    }

    fun printReport(result: DiagnosticResult, stdout: PrintWriter, stderr: PrintWriter): Int {
        stdout.println("Qutivex Environment\n")
        stdout.printf("%-12s%-12s%s%n", "Qutivex", result.qutivexVersion, "OK")
        stdout.printf("%-12s%-12s%s%n", "Platform", result.platform, "OK")

        if (result.javaPath == null) {
            stdout.printf("%-12s%-12s%s%n", "Java", "not found", "ERROR")
            stdout.printf("%-12s%-12s%s%n", "JAVA_HOME", result.javaHome ?: "not set", "ERROR")
        } else if (result.javaMajorVersion != 21) {
            stdout.printf("%-12s%-12s%s%n", "Java", "${result.javaMajorVersion} (need 21)", "ERROR")
            stdout.printf("%-12s%-12s%s%n", "JAVA_HOME", if (result.javaHome != null) "detected" else "from PATH", "WARNING")
        } else {
            stdout.printf("%-12s%-12s%s%n", "Java", result.javaVersion ?: "21", "OK")
            stdout.printf("%-12s%-12s%s%n", "JAVA_HOME", if (result.javaHome != null) "detected" else "from PATH", "OK")
        }

        stdout.printf("%-12s%-12s%s%n", "Gradle", result.gradleStatus, "OK")
        stdout.printf("%-12s%-12s%s%n", "Repository", if (result.repositoryReachable) "reachable" else "unreachable", if (result.repositoryReachable) "OK" else "WARNING")
        stdout.println()

        if (!result.javaOk) {
            if (result.javaPath == null) {
                stderr.println("Qutivex requires JDK 21.")
                stderr.println()
                stderr.println("No compatible JDK installation was found.")
                stderr.println()
                stderr.println("Install JDK 21 and ensure either:")
                stderr.println("- JAVA_HOME points to the JDK, or")
                stderr.println("- java.exe is available on PATH.")
                stderr.println()
                stderr.println("Then run Qutivex again.")
            } else {
                stderr.println("Detected Java: ${result.javaMajorVersion ?: "unknown"}")
                stderr.println("Required Java: 21")
                stderr.println()
                stderr.println("Please install JDK 21 to use this version of Qutivex.")
            }
            return 1
        }

        return 0
    }

    private fun findJava(): Pair<Path?, String?> {
        val envJavaHome = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }
        if (envJavaHome != null) {
            val homePath = Path.of(envJavaHome)
            val exec = if (isWindows()) homePath.resolve("bin/java.exe") else homePath.resolve("bin/java")
            if (Files.isExecutable(exec)) {
                return Pair(exec, envJavaHome)
            }
        }

        val propJavaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() }
        if (propJavaHome != null) {
            val homePath = Path.of(propJavaHome)
            val exec = if (isWindows()) homePath.resolve("bin/java.exe") else homePath.resolve("bin/java")
            if (Files.isExecutable(exec)) {
                return Pair(exec, propJavaHome)
            }
        }

        val pathEnv = System.getenv("PATH") ?: ""
        val pathSeparator = if (isWindows()) ";" else ":"
        val binaryName = if (isWindows()) "java.exe" else "java"
        for (part in pathEnv.split(pathSeparator)) {
            val candidate = Path.of(part).resolve(binaryName)
            if (Files.isExecutable(candidate)) {
                return Pair(candidate, null)
            }
        }

        return Pair(null, null)
    }

    private fun getJavaVersion(javaPath: Path?): Pair<String?, Int?> {
        if (javaPath == null) return Pair(null, null)
        return try {
            val process = ProcessBuilder(listOf(javaPath.toString(), "-version"))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val versionRegex = Regex("\"([0-9._]+)\"")
            val match = versionRegex.find(output)
            val versionStr = match?.groupValues?.get(1) ?: System.getProperty("java.version")
            val major = parseMajorVersion(versionStr)
            Pair(versionStr, major)
        } catch (_: Exception) {
            val fallback = System.getProperty("java.version")
            Pair(fallback, parseMajorVersion(fallback))
        }
    }

    private fun parseMajorVersion(version: String?): Int? {
        if (version == null) return null
        return try {
            val clean = version.trim().removePrefix("1.")
            val firstToken = clean.takeWhile { it.isDigit() }
            firstToken.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun isRepositoryReachable(): Boolean {
        return try {
            val uri = URI("https://repo.maven.apache.org/maven2/")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.responseCode in 200..399
        } catch (_: Exception) {
            false
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true
}
