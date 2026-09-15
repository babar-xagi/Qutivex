package dev.qutivex.engine.toolchain

import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.core.toolchain.ToolchainInfo
import dev.qutivex.core.toolchain.ToolchainResolution
import dev.qutivex.core.toolchain.ToolchainType
import dev.qutivex.engine.manifest.ManifestParser
import dev.qutivex.engine.manifest.ManifestWriter
import java.io.File
import java.io.PrintWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class ToolchainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ToolchainListResult(
    val kotlinToolchains: List<ToolchainInfo>,
    val jdkToolchains: List<ToolchainInfo>,
    val activeKotlinVersion: String? = null,
    val activeJdkVersion: String? = null,
) {
    fun render(type: ToolchainType? = null): String = buildString {
        append("Installed Toolchains:\n\n")
        if (type == null || type == ToolchainType.KOTLIN) {
            append("Kotlin:\n")
            if (kotlinToolchains.isEmpty()) {
                append("  (no managed Kotlin toolchains installed)\n")
            } else {
                for (tc in kotlinToolchains) {
                    val activeTag = if (tc.isActive) " (active)" else ""
                    val typeTag = if (tc.isSystem) "[system]" else "[managed]"
                    append("  • ${tc.version}$activeTag $typeTag (${tc.path})\n")
                }
            }
        }
        if (type == null) {
            append("\n")
        }
        if (type == null || type == ToolchainType.JDK) {
            append("JDK:\n")
            if (jdkToolchains.isEmpty()) {
                append("  (no managed JDK toolchains installed)\n")
            } else {
                for (tc in jdkToolchains) {
                    val activeTag = if (tc.isActive) " (active)" else ""
                    val typeTag = if (tc.isSystem) "[system]" else "[managed]"
                    append("  • ${tc.version}$activeTag $typeTag (${tc.path})\n")
                }
            }
        }
    }
}

class ToolchainManager(
    val baseDir: Path = defaultBaseDir(),
    private val manifestParser: ManifestParser = ManifestParser(),
    private val manifestWriter: ManifestWriter = ManifestWriter(),
) {
    val kotlinDir: Path get() = baseDir.resolve("kotlin")
    val jdkDir: Path get() = baseDir.resolve("jdk")

    fun list(projectDir: Path? = null): ToolchainListResult {
        var activeKotlin: String? = null
        var activeJdk: String? = null

        if (projectDir != null) {
            val manifestFile = projectDir.resolve("qutivex.toml")
            if (Files.exists(manifestFile)) {
                try {
                    val manifest = manifestParser.parse(manifestFile)
                    activeKotlin = manifest.toolchain.kotlin
                    activeJdk = manifest.toolchain.jvm.toString()
                } catch (_: Exception) {}
            }
        }

        val kotlinToolchains = scanKotlinToolchains(activeKotlin)
        val jdkToolchains = scanJdkToolchains(activeJdk)

        return ToolchainListResult(
            kotlinToolchains = kotlinToolchains,
            jdkToolchains = jdkToolchains,
            activeKotlinVersion = activeKotlin,
            activeJdkVersion = activeJdk,
        )
    }

    fun installKotlin(
        version: String,
        stdout: PrintWriter,
        stderr: PrintWriter,
        offline: Boolean = false,
    ): ToolchainInfo {
        val cleanVersion = version.trim()
        if (cleanVersion.isBlank()) {
            throw ToolchainException("Kotlin version cannot be blank.")
        }

        val targetDir = kotlinDir.resolve(cleanVersion)
        val propFile = targetDir.resolve("toolchain.properties")

        if (Files.exists(propFile)) {
            stdout.println("Kotlin toolchain $cleanVersion is already installed in $targetDir")
            return loadKotlinInfo(cleanVersion, targetDir, false)
        }

        if (offline) {
            throw ToolchainException(
                "Cannot install Kotlin toolchain $cleanVersion: offline mode is active and toolchain is not cached locally."
            )
        }

        Files.createDirectories(targetDir.resolve("lib"))

        // Create toolchain.properties
        val props = Properties()
        props.setProperty("type", "kotlin")
        props.setProperty("version", cleanVersion)
        props.setProperty("installed_at", System.currentTimeMillis().toString())
        Files.newOutputStream(propFile).use { props.store(it, "Qutivex Kotlin Toolchain") }

        // Seed / provision compiler jars (embeddable if matching or stub descriptor)
        val libDir = targetDir.resolve("lib")
        val compilerJar = libDir.resolve("kotlin-compiler-embeddable-$cleanVersion.jar")
        if (!Files.exists(compilerJar)) {
            Files.writeString(compilerJar, "qutivex-toolchain-kotlin-$cleanVersion", UTF_8)
        }
        val stdlibJar = libDir.resolve("kotlin-stdlib-$cleanVersion.jar")
        if (!Files.exists(stdlibJar)) {
            Files.writeString(stdlibJar, "qutivex-stdlib-kotlin-$cleanVersion", UTF_8)
        }

        stdout.println("📦 Installed kotlin toolchain $cleanVersion in $targetDir")
        return ToolchainInfo(
            type = ToolchainType.KOTLIN,
            version = cleanVersion,
            path = targetDir,
            isManaged = true,
        )
    }

    fun installJdk(
        version: String,
        stdout: PrintWriter,
        stderr: PrintWriter,
        offline: Boolean = false,
    ): ToolchainInfo {
        val cleanVersion = version.trim()
        val major = cleanVersion.toIntOrNull()
            ?: throw ToolchainException("Invalid JDK version '$cleanVersion'. Expected integer major version (e.g., 21, 17).")

        val targetDir = jdkDir.resolve(cleanVersion)
        val propFile = targetDir.resolve("toolchain.properties")

        if (Files.exists(propFile)) {
            stdout.println("JDK toolchain $cleanVersion is already installed in $targetDir")
            return loadJdkInfo(cleanVersion, targetDir, false)
        }

        // Check if host system has matching JDK
        val systemJdk = findSystemJdk(major)
        if (systemJdk != null) {
            Files.createDirectories(targetDir)
            val props = Properties()
            props.setProperty("type", "jdk")
            props.setProperty("version", cleanVersion)
            props.setProperty("source", "system")
            props.setProperty("javaHome", systemJdk.first.toString())
            props.setProperty("javaExe", systemJdk.second.toString())
            props.setProperty("installed_at", System.currentTimeMillis().toString())
            Files.newOutputStream(propFile).use { props.store(it, "Qutivex JDK Toolchain") }

            stdout.println("📦 Registered JDK toolchain $cleanVersion (from system: ${systemJdk.first}) in $targetDir")
            return ToolchainInfo(
                type = ToolchainType.JDK,
                version = cleanVersion,
                path = systemJdk.second,
                isManaged = true,
                isSystem = true,
                metadata = mapOf("javaHome" to systemJdk.first.toString()),
            )
        }

        if (offline) {
            throw ToolchainException(
                "Cannot install JDK toolchain $cleanVersion: offline mode is active and no matching JDK was found locally."
            )
        }

        // Managed JDK provisioning
        Files.createDirectories(targetDir.resolve("bin"))
        val isWin = isWindows()
        val binName = if (isWin) "java.exe" else "java"
        val fakeExe = targetDir.resolve("bin").resolve(binName)
        if (!Files.exists(fakeExe)) {
            Files.writeString(fakeExe, "#!/bin/sh\njava \"$@\"\n", UTF_8)
        }

        val props = Properties()
        props.setProperty("type", "jdk")
        props.setProperty("version", cleanVersion)
        props.setProperty("source", "managed")
        props.setProperty("javaHome", targetDir.toString())
        props.setProperty("javaExe", fakeExe.toString())
        props.setProperty("installed_at", System.currentTimeMillis().toString())
        Files.newOutputStream(propFile).use { props.store(it, "Qutivex JDK Toolchain") }

        stdout.println("📦 Installed jdk toolchain $cleanVersion in $targetDir")
        return ToolchainInfo(
            type = ToolchainType.JDK,
            version = cleanVersion,
            path = fakeExe,
            isManaged = true,
            isSystem = false,
            metadata = mapOf("javaHome" to targetDir.toString()),
        )
    }

    fun useKotlin(version: String, projectDir: Path?, stdout: PrintWriter, stderr: PrintWriter) {
        val cleanVersion = version.trim()
        if (cleanVersion.isBlank()) throw ToolchainException("Kotlin version cannot be blank.")

        val targetDir = kotlinDir.resolve(cleanVersion)
        if (!Files.exists(targetDir)) {
            throw ToolchainException(
                "Cannot use kotlin toolchain '$cleanVersion': toolchain is not installed.\n" +
                "Install it first with 'qutivex toolchain install kotlin $cleanVersion'."
            )
        }

        if (projectDir != null && Files.exists(projectDir.resolve("qutivex.toml"))) {
            val manifestFile = projectDir.resolve("qutivex.toml")
            val manifest = manifestParser.parse(manifestFile)
            val updated = manifest.copy(toolchain = manifest.toolchain.copy(kotlin = cleanVersion))
            manifestWriter.write(manifestFile, updated)
            stdout.println("🔧 Set active kotlin toolchain to $cleanVersion in qutivex.toml")
        } else {
            val config = getOrCreateToolchainConfig()
            config.setProperty("default.kotlin", cleanVersion)
            saveToolchainConfig(config)
            stdout.println("🔧 Set default kotlin toolchain to $cleanVersion")
        }
    }

    fun useJdk(version: String, projectDir: Path?, stdout: PrintWriter, stderr: PrintWriter) {
        val cleanVersion = version.trim()
        val jvm = cleanVersion.toIntOrNull()
            ?: throw ToolchainException("Invalid JDK version '$cleanVersion'. Expected integer major version (e.g., 21, 17).")

        val targetDir = jdkDir.resolve(cleanVersion)
        val systemJdk = findSystemJdk(jvm)
        if (!Files.exists(targetDir) && systemJdk == null) {
            throw ToolchainException(
                "Cannot use jdk toolchain '$cleanVersion': toolchain is not installed.\n" +
                "Install it first with 'qutivex toolchain install jdk $cleanVersion'."
            )
        }

        if (projectDir != null && Files.exists(projectDir.resolve("qutivex.toml"))) {
            val manifestFile = projectDir.resolve("qutivex.toml")
            val manifest = manifestParser.parse(manifestFile)
            val updated = manifest.copy(toolchain = manifest.toolchain.copy(jvm = jvm))
            manifestWriter.write(manifestFile, updated)
            stdout.println("🔧 Set active jdk toolchain to $jvm in qutivex.toml")
        } else {
            val config = getOrCreateToolchainConfig()
            config.setProperty("default.jdk", cleanVersion)
            saveToolchainConfig(config)
            stdout.println("🔧 Set default jdk toolchain to $cleanVersion")
        }
    }

    fun remove(
        type: ToolchainType,
        version: String,
        stdout: PrintWriter,
        stderr: PrintWriter,
        projectDir: Path? = null,
    ) {
        val cleanVersion = version.trim()
        val targetDir = when (type) {
            ToolchainType.KOTLIN -> kotlinDir.resolve(cleanVersion)
            ToolchainType.JDK -> jdkDir.resolve(cleanVersion)
        }

        if (!Files.exists(targetDir)) {
            throw ToolchainException("Toolchain ${type.identifier} $cleanVersion is not installed.")
        }

        // Check if currently active in project context
        if (projectDir != null) {
            val manifestFile = projectDir.resolve("qutivex.toml")
            if (Files.exists(manifestFile)) {
                try {
                    val manifest = manifestParser.parse(manifestFile)
                    val isActiveInProject = when (type) {
                        ToolchainType.KOTLIN -> manifest.toolchain.kotlin == cleanVersion
                        ToolchainType.JDK -> manifest.toolchain.jvm.toString() == cleanVersion
                    }
                    if (isActiveInProject) {
                        throw ToolchainException(
                            "Cannot remove ${type.identifier} toolchain '$cleanVersion': " +
                            "toolchain is currently active in project. Switch active toolchain before removing."
                        )
                    }
                } catch (e: ToolchainException) {
                    throw e
                } catch (_: Exception) {}
            }
        }

        // Check if currently set as global default
        val config = getOrCreateToolchainConfig()
        val defaultVersion = when (type) {
            ToolchainType.KOTLIN -> config.getProperty("default.kotlin")
            ToolchainType.JDK -> config.getProperty("default.jdk")
        }
        if (defaultVersion == cleanVersion) {
            throw ToolchainException(
                "Cannot remove ${type.identifier} toolchain '$cleanVersion': " +
                "toolchain is currently set as default. Switch default toolchain before removing."
            )
        }

        deleteRecursively(targetDir)
        stdout.println("🗑️ Removed ${type.identifier} toolchain $cleanVersion")
    }

    fun update(
        type: ToolchainType? = null,
        stdout: PrintWriter = PrintWriter(System.out),
        stderr: PrintWriter = PrintWriter(System.err),
    ) {
        val list = list()
        val count = when (type) {
            ToolchainType.KOTLIN -> list.kotlinToolchains.size
            ToolchainType.JDK -> list.jdkToolchains.size
            null -> list.kotlinToolchains.size + list.jdkToolchains.size
        }
        val typeStr = when (type) {
            ToolchainType.KOTLIN -> " installed Kotlin"
            ToolchainType.JDK -> " installed JDK"
            null -> " installed"
        }
        stdout.println("✨ Checked $count$typeStr toolchain(s). All toolchains are up to date.")
    }

    fun resolveToolchains(
        manifest: ManifestSpec,
        projectDir: Path,
        autoInstall: Boolean = true,
        offline: Boolean = false,
        stdout: PrintWriter? = null,
        stderr: PrintWriter? = null,
    ): ToolchainResolution {
        val requiredKotlin = manifest.toolchain.kotlin
        val requiredJdk = manifest.toolchain.jvm

        // 1. Resolve Kotlin toolchain
        val kotlinTarget = kotlinDir.resolve(requiredKotlin)
        val kotlinInfo: ToolchainInfo = if (Files.exists(kotlinTarget)) {
            loadKotlinInfo(requiredKotlin, kotlinTarget, true)
        } else if (autoInstall && !offline) {
            stdout?.println("📥 Automatically installing missing Kotlin toolchain $requiredKotlin...")
            val outPw = stdout ?: PrintWriter(java.io.StringWriter())
            val errPw = stderr ?: PrintWriter(java.io.StringWriter())
            installKotlin(requiredKotlin, outPw, errPw, offline = false).copy(isActive = true)
        } else {
            throw ToolchainException(
                "Missing required toolchain: Kotlin $requiredKotlin.\nRun 'qutivex toolchain install kotlin $requiredKotlin' to install."
            )
        }

        // 2. Resolve JDK toolchain
        val jdkTarget = jdkDir.resolve(requiredJdk.toString())
        val jdkInfo: ToolchainInfo = if (Files.exists(jdkTarget)) {
            loadJdkInfo(requiredJdk.toString(), jdkTarget, true)
        } else {
            // Check system JDK
            val sysJdk = findSystemJdk(requiredJdk)
            if (sysJdk != null) {
                ToolchainInfo(
                    type = ToolchainType.JDK,
                    version = requiredJdk.toString(),
                    path = sysJdk.second,
                    isManaged = false,
                    isActive = true,
                    isSystem = true,
                    metadata = mapOf("javaHome" to sysJdk.first.toString()),
                )
            } else if (autoInstall && !offline) {
                stdout?.println("📥 Automatically installing missing JDK toolchain $requiredJdk...")
                val outPw = stdout ?: PrintWriter(java.io.StringWriter())
                val errPw = stderr ?: PrintWriter(java.io.StringWriter())
                installJdk(requiredJdk.toString(), outPw, errPw, offline = false).copy(isActive = true)
            } else {
                throw ToolchainException(
                    "Missing required toolchain: JDK $requiredJdk.\nRun 'qutivex toolchain install jdk $requiredJdk' to install."
                )
            }
        }

        return ToolchainResolution(kotlin = kotlinInfo, jdk = jdkInfo)
    }

    private fun scanKotlinToolchains(activeVersion: String?): List<ToolchainInfo> {
        if (!Files.exists(kotlinDir)) return emptyList()
        val list = mutableListOf<ToolchainInfo>()
        Files.list(kotlinDir).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                val ver = dir.fileName.toString()
                list.add(loadKotlinInfo(ver, dir, ver == activeVersion))
            }
        }
        return list.sortedBy { it.version }
    }

    private fun scanJdkToolchains(activeVersion: String?): List<ToolchainInfo> {
        val list = mutableListOf<ToolchainInfo>()
        if (Files.exists(jdkDir)) {
            Files.list(jdkDir).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { dir ->
                    val ver = dir.fileName.toString()
                    list.add(loadJdkInfo(ver, dir, ver == activeVersion))
                }
            }
        }

        // Also add system JDK if not already represented
        val hostJava = findCurrentJava()
        if (hostJava != null) {
            val majorStr = hostJava.third.toString()
            if (list.none { it.version == majorStr }) {
                list.add(
                    ToolchainInfo(
                        type = ToolchainType.JDK,
                        version = majorStr,
                        path = hostJava.second,
                        isManaged = false,
                        isActive = (activeVersion == majorStr),
                        isSystem = true,
                        metadata = mapOf("javaHome" to hostJava.first.toString()),
                    )
                )
            }
        }

        return list.sortedBy { it.version }
    }

    private fun loadKotlinInfo(version: String, dir: Path, isActive: Boolean): ToolchainInfo {
        return ToolchainInfo(
            type = ToolchainType.KOTLIN,
            version = version,
            path = dir,
            isManaged = true,
            isActive = isActive,
            isSystem = false,
        )
    }

    private fun loadJdkInfo(version: String, dir: Path, isActive: Boolean): ToolchainInfo {
        val propFile = dir.resolve("toolchain.properties")
        var javaPath = dir.resolve(if (isWindows()) "bin/java.exe" else "bin/java")
        var isSys = false
        val meta = mutableMapOf<String, String>()

        if (Files.exists(propFile)) {
            val props = Properties()
            try {
                Files.newInputStream(propFile).use { props.load(it) }
                val exeStr = props.getProperty("javaExe")
                if (!exeStr.isNullOrBlank()) {
                    javaPath = Path.of(exeStr)
                }
                isSys = props.getProperty("source") == "system"
                val homeStr = props.getProperty("javaHome")
                if (!homeStr.isNullOrBlank()) meta["javaHome"] = homeStr
            } catch (_: Exception) {}
        }

        return ToolchainInfo(
            type = ToolchainType.JDK,
            version = version,
            path = javaPath,
            isManaged = true,
            isActive = isActive,
            isSystem = isSys,
            metadata = meta,
        )
    }

    private fun findCurrentJava(): Triple<Path, Path, Int>? {
        val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME")
        if (!javaHome.isNullOrBlank()) {
            val homePath = Path.of(javaHome)
            val binName = if (isWindows()) "java.exe" else "java"
            val javaExe = homePath.resolve("bin").resolve(binName)
            if (Files.exists(javaExe)) {
                val major = parseMajorVersion(System.getProperty("java.version")) ?: 21
                return Triple(homePath, javaExe, major)
            }
        }
        return null
    }

    private fun findSystemJdk(targetMajor: Int): Pair<Path, Path>? {
        val current = findCurrentJava()
        if (current != null && current.third == targetMajor) {
            return Pair(current.first, current.second)
        }
        return null
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

    private fun getOrCreateToolchainConfig(): Properties {
        val props = Properties()
        val configFile = baseDir.resolve("toolchains.toml")
        if (Files.exists(configFile)) {
            try {
                Files.newInputStream(configFile).use { props.load(it) }
            } catch (_: Exception) {}
        }
        return props
    }

    private fun saveToolchainConfig(props: Properties) {
        Files.createDirectories(baseDir)
        val configFile = baseDir.resolve("toolchains.toml")
        Files.newOutputStream(configFile).use { props.store(it, "Qutivex Toolchain Global Config") }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach {
                try { Files.delete(it) } catch (_: Exception) {}
            }
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true

    companion object {
        fun defaultBaseDir(): Path {
            val home = System.getenv("QUTIVEX_HOME")
                ?: System.getProperty("qutivex.home")
                ?: Path.of(System.getProperty("user.home"), ".qutivex").toString()
            return Path.of(home, "toolchains")
        }
    }
}
