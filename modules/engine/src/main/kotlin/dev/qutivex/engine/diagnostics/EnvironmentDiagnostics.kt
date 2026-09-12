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

data class DiagnosticRow(val label: String, val value: String, val status: String)

class DiagnosticTableFormatter {
    fun format(rows: List<DiagnosticRow>): String {
        val labelWidth = maxOf(12, (rows.maxOfOrNull { it.label.length } ?: 10) + 2)
        val valueWidth = maxOf(19, (rows.maxOfOrNull { it.value.length } ?: 16) + 3)
        return buildString {
            for (row in rows) {
                append(row.label.padEnd(labelWidth))
                append(row.value.padEnd(valueWidth))
                append(row.status)
                append("\n")
            }
        }
    }
}

class EnvironmentDiagnostics(
    private val tableFormatter: DiagnosticTableFormatter = DiagnosticTableFormatter(),
) {
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

        val javaVal: String
        val javaStatus: String
        val javaHomeVal: String
        val javaHomeStatus: String

        if (result.javaPath == null) {
            javaVal = "not found"
            javaStatus = "ERROR"
            javaHomeVal = result.javaHome ?: "not set"
            javaHomeStatus = "ERROR"
        } else if (result.javaMajorVersion != 21) {
            javaVal = "${result.javaMajorVersion} (need 21)"
            javaStatus = "ERROR"
            javaHomeVal = if (result.javaHome != null) "detected" else "from PATH"
            javaHomeStatus = "WARNING"
        } else {
            javaVal = result.javaVersion ?: "21"
            javaStatus = "OK"
            javaHomeVal = if (result.javaHome != null) "detected" else "from PATH"
            javaHomeStatus = "OK"
        }

        val rows = listOf(
            DiagnosticRow("Qutivex", result.qutivexVersion, "OK"),
            DiagnosticRow("Platform", result.platform, "OK"),
            DiagnosticRow("Java", javaVal, javaStatus),
            DiagnosticRow("JAVA_HOME", javaHomeVal, javaHomeStatus),
            DiagnosticRow("Gradle", result.gradleStatus, "OK"),
            DiagnosticRow(
                "Repository",
                if (result.repositoryReachable) "reachable" else "unreachable",
                if (result.repositoryReachable) "OK" else "WARNING",
            ),
        )

        stdout.print(tableFormatter.format(rows))
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
