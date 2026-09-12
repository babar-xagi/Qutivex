package dev.qutivex.engine.build

import dev.qutivex.core.lockfile.LockfileSpec
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.dependency.ArtifactCache
import dev.qutivex.engine.dependency.HttpRepositoryClient
import dev.qutivex.engine.dependency.LocalArtifactCache
import dev.qutivex.engine.dependency.RepositoryClient
import java.nio.file.Files
import java.nio.file.Path

data class JUnitVersionAlignment(
    val platformVersion: String,
    val jupiterVersion: String,
)

data class ParsedJarInfo(
    val artifactName: String,
    val version: String?,
)

/**
 * Builds compile, runtime, and test classpaths from Qutivex dependency state (qutivex.lock + artifact cache)
 * and toolchain runtime libraries. Guarantees deterministic JUnit Platform version alignment and purges
 * conflicting versions.
 */
class ClasspathBuilder(
    private val artifactCache: ArtifactCache = LocalArtifactCache(),
    private val repositoryClient: RepositoryClient = HttpRepositoryClient(),
) {

    /**
     * Classpath used to compile src/main/kotlin.
     * Contains runtime dependency JARs from lockfile + Kotlin stdlib.
     */
    fun buildCompileClasspath(
        projectDir: Path,
        manifest: ManifestSpec,
        lockfile: LockfileSpec?,
    ): List<Path> {
        val result = LinkedHashSet<Path>()

        // 1. Runtime dependency JARs from lockfile
        if (lockfile != null) {
            for (pkg in lockfile.packages.filter { it.scope == "runtime" }) {
                val jar = artifactCache.findArtifact(pkg.group, pkg.artifact, pkg.version)
                if (jar != null && Files.exists(jar)) {
                    result.add(jar)
                }
            }
        }

        // 2. Ensure Kotlin stdlib and core annotations are present
        ensureKotlinToolchainJars(manifest, lockfile, result)

        return result.filter { Files.exists(it) }.toList()
    }

    /**
     * Classpath used to run the application (qutivex run).
     * Contains main classes dir, main resources dir, runtime dependency JARs, and Kotlin stdlib.
     */
    fun buildRuntimeClasspath(
        projectDir: Path,
        manifest: ManifestSpec,
        lockfile: LockfileSpec?,
    ): List<Path> {
        val result = LinkedHashSet<Path>()

        // 1. Output classes and resources
        val mainClasses = projectDir.resolve("build/classes/kotlin/main")
        val mainResources = projectDir.resolve("build/resources/main")
        if (Files.exists(mainClasses)) result.add(mainClasses)
        if (Files.exists(mainResources)) result.add(mainResources)

        // 2. Runtime dependency JARs from lockfile
        if (lockfile != null) {
            for (pkg in lockfile.packages.filter { it.scope == "runtime" }) {
                val jar = artifactCache.findArtifact(pkg.group, pkg.artifact, pkg.version)
                if (jar != null && Files.exists(jar)) {
                    result.add(jar)
                }
            }
        }

        // 3. Ensure Kotlin stdlib
        ensureKotlinToolchainJars(manifest, lockfile, result)

        return result.filter { Files.exists(it) }.toList()
    }

    /**
     * Classpath used to compile src/test/kotlin.
     * Contains main classes dir, main resources dir, runtime and test dependency JARs,
     * Kotlin stdlib, and test libraries (kotlin-test, junit).
     */
    fun buildTestCompileClasspath(
        projectDir: Path,
        manifest: ManifestSpec,
        lockfile: LockfileSpec?,
    ): List<Path> {
        val alignment = detectJUnitVersionAlignment(manifest, lockfile)
        val rawList = mutableListOf<Path>()

        // 1. Main classes & resources
        val mainClasses = projectDir.resolve("build/classes/kotlin/main")
        val mainResources = projectDir.resolve("build/resources/main")
        if (Files.exists(mainClasses)) rawList.add(mainClasses)
        if (Files.exists(mainResources)) rawList.add(mainResources)

        // 2. Runtime and test dependency JARs from lockfile
        if (lockfile != null) {
            for (pkg in lockfile.packages) {
                val jar = artifactCache.findArtifact(pkg.group, pkg.artifact, pkg.version)
                if (jar != null && Files.exists(jar)) {
                    rawList.add(jar)
                }
            }
        }

        // 3. Kotlin stdlib and test libraries
        val toolchainSet = LinkedHashSet<Path>()
        ensureKotlinToolchainJars(manifest, lockfile, toolchainSet)
        ensureTestFrameworkJars(manifest, toolchainSet, alignment)
        rawList.addAll(toolchainSet)

        return alignAndDeduplicateClasspath(rawList, alignment)
    }

    /**
     * Classpath used to execute JUnit platform tests.
     * Contains test classes, test resources, main classes, main resources,
     * runtime & test dependency JARs, Kotlin stdlib, and fully aligned JUnit platform runner JARs.
     * Conflicting or unaligned JUnit platform/engine jars are purged deterministically.
     */
    fun buildTestRuntimeClasspath(
        projectDir: Path,
        manifest: ManifestSpec,
        lockfile: LockfileSpec?,
    ): List<Path> {
        val alignment = detectJUnitVersionAlignment(manifest, lockfile)
        val rawList = mutableListOf<Path>()

        // 1. Test classes & resources
        val testClasses = projectDir.resolve("build/classes/kotlin/test")
        val testResources = projectDir.resolve("build/resources/test")
        if (Files.exists(testClasses)) rawList.add(testClasses)
        if (Files.exists(testResources)) rawList.add(testResources)

        // 2. Main classes & resources
        val mainClasses = projectDir.resolve("build/classes/kotlin/main")
        val mainResources = projectDir.resolve("build/resources/main")
        if (Files.exists(mainClasses)) rawList.add(mainClasses)
        if (Files.exists(mainResources)) rawList.add(mainResources)

        // 3. Runtime & test dependencies from lockfile
        if (lockfile != null) {
            for (pkg in lockfile.packages) {
                val jar = artifactCache.findArtifact(pkg.group, pkg.artifact, pkg.version)
                if (jar != null && Files.exists(jar)) {
                    rawList.add(jar)
                }
            }
        }

        // 4. Kotlin stdlib
        val toolchainSet = LinkedHashSet<Path>()
        ensureKotlinToolchainJars(manifest, lockfile, toolchainSet)
        ensureTestFrameworkJars(manifest, toolchainSet, alignment)
        rawList.addAll(toolchainSet)

        // 5. Ensure fully aligned JUnit platform runner JARs
        ensureAlignedJUnitJars(rawList, alignment)

        // 6. Purge conflicting versions and deduplicate
        return alignAndDeduplicateClasspath(rawList, alignment)
    }

    fun resolveArtifact(group: String, artifact: String, version: String): Path? {
        // 1. Check local artifact cache
        val cached = artifactCache.findArtifact(group, artifact, version)
        if (cached != null && Files.exists(cached)) return cached

        // 2. Check bundled classes if version matches bundled
        val isPlatformMatch = isJUnitPlatformArtifact(artifact) && version == DEFAULT_PLATFORM_VERSION
        val isJupiterMatch = isJUnitJupiterArtifact(artifact) && version == DEFAULT_JUPITER_VERSION
        if (isPlatformMatch || isJupiterMatch) {
            val bundled = findBundledJarForArtifact(artifact)
            if (bundled != null && Files.exists(bundled)) {
                val parsed = parseJarInfo(bundled)
                if (parsed?.version == null || parsed.version == version) {
                    return bundled
                }
            }
        }

        // 3. Fallback: try fetching from repository if needed
        return try {
            val dest = artifactCache.getExpectedLocation(group, artifact, version)
            val res = repositoryClient.fetchArtifact(group = group, artifact = artifact, version = version, destination = dest)
            if (Files.exists(res.file)) res.file else null
        } catch (_: Exception) {
            null
        }
    }

    private fun findJarForClassName(className: String): Path? {
        return try {
            val clazz = Class.forName(className)
            findJarForClass(clazz)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findBundledJarForArtifact(artifact: String): Path? {
        return when (artifact) {
            "junit-platform-launcher" -> findJarForClassName("org.junit.platform.launcher.Launcher")
            "junit-platform-engine" -> findJarForClassName("org.junit.platform.engine.TestEngine")
            "junit-platform-commons" -> findJarForClassName("org.junit.platform.commons.JUnitException")
            "junit-jupiter-engine" -> findJarForClassName("org.junit.jupiter.engine.JupiterTestEngine")
            "junit-jupiter-api" -> findJarForClassName("org.junit.jupiter.api.Test")
            "opentest4j" -> findJarForClassName("org.opentest4j.AssertionFailedError")
            "apiguardian-api" -> findJarForClassName("org.apiguardian.api.API")
            else -> null
        }
    }

    private fun ensureAlignedJUnitJars(
        rawList: MutableList<Path>,
        alignment: JUnitVersionAlignment,
    ) {
        val requiredJars = listOf(
            Triple("org.junit.platform", "junit-platform-launcher", alignment.platformVersion),
            Triple("org.junit.platform", "junit-platform-engine", alignment.platformVersion),
            Triple("org.junit.platform", "junit-platform-commons", alignment.platformVersion),
            Triple("org.junit.jupiter", "junit-jupiter-engine", alignment.jupiterVersion),
            Triple("org.junit.jupiter", "junit-jupiter-api", alignment.jupiterVersion),
        )

        for ((group, artifact, version) in requiredJars) {
            val jar = resolveArtifact(group, artifact, version)
            if (jar != null && Files.exists(jar)) {
                rawList.add(jar)
            }
        }

        findJarForClassName("org.opentest4j.AssertionFailedError")?.let { rawList.add(it) }
        findJarForClassName("org.apiguardian.api.API")?.let { rawList.add(it) }
    }

    private fun ensureKotlinToolchainJars(
        manifest: ManifestSpec,
        lockfile: LockfileSpec?,
        result: MutableSet<Path>,
    ) {
        val kotlinVersion = manifest.toolchain.kotlin

        // Check if stdlib is already in lockfile and cached
        val cachedStdlib = artifactCache.findArtifact("org.jetbrains.kotlin", "kotlin-stdlib", kotlinVersion)
        if (cachedStdlib != null && Files.exists(cachedStdlib)) {
            result.add(cachedStdlib)
        } else {
            findJarForClass(KotlinVersion::class.java)?.let { result.add(it) }
        }

        // Annotations
        val cachedAnnotations = artifactCache.findArtifact("org.jetbrains", "annotations", "13.0")
            ?: artifactCache.findArtifact("org.jetbrains", "annotations", "23.0.0")
        if (cachedAnnotations != null && Files.exists(cachedAnnotations)) {
            result.add(cachedAnnotations)
        } else {
            findJarForClassName("org.jetbrains.annotations.Nullable")?.let { result.add(it) }
        }
    }

    private fun ensureTestFrameworkJars(
        manifest: ManifestSpec,
        result: MutableSet<Path>,
        alignment: JUnitVersionAlignment? = null,
    ) {
        val kotlinVersion = manifest.toolchain.kotlin

        for (artifact in listOf("kotlin-test", "kotlin-test-junit5")) {
            val cached = artifactCache.findArtifact("org.jetbrains.kotlin", artifact, kotlinVersion)
            if (cached != null && Files.exists(cached)) {
                result.add(cached)
            }
        }

        val testClassNames = listOf(
            "kotlin.test.Test",
            "kotlin.test.junit5.JUnit5Asserter",
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.Assertions",
        )
        for (className in testClassNames) {
            findJarForClassName(className)?.let { result.add(it) }
        }

        if (alignment != null) {
            val jupiterApi = resolveArtifact("org.junit.jupiter", "junit-jupiter-api", alignment.jupiterVersion)
            if (jupiterApi != null && Files.exists(jupiterApi)) {
                result.add(jupiterApi)
            }
        }
    }

    companion object {
        const val DEFAULT_JUPITER_VERSION = "5.12.2"
        const val DEFAULT_PLATFORM_VERSION = "1.12.2"
        const val DEFAULT_KOTLIN_VERSION = "2.4.10"

        private val JAR_NAME_REGEX = Regex("""^([a-zA-Z0-9._-]+?)-([0-9][a-zA-Z0-9._-]*)\.jar$""")

        fun mapJupiterToPlatform(jupiterVersion: String): String {
            return if (jupiterVersion.startsWith("5.")) {
                "1." + jupiterVersion.substring(2)
            } else {
                jupiterVersion
            }
        }

        fun mapPlatformToJupiter(platformVersion: String): String {
            return if (platformVersion.startsWith("1.")) {
                "5." + platformVersion.substring(2)
            } else {
                platformVersion
            }
        }

        fun detectJUnitVersionAlignment(manifest: ManifestSpec?, lockfile: LockfileSpec?): JUnitVersionAlignment {
            if (lockfile != null) {
                for (pkg in lockfile.packages) {
                    if (pkg.group == "org.junit.platform") {
                        val pVer = pkg.version
                        return JUnitVersionAlignment(pVer, mapPlatformToJupiter(pVer))
                    }
                }
                for (pkg in lockfile.packages) {
                    if (pkg.group == "org.junit.jupiter") {
                        val jVer = pkg.version
                        return JUnitVersionAlignment(mapJupiterToPlatform(jVer), jVer)
                    }
                }
            }

            if (manifest != null) {
                val allDeps = manifest.testDependencies + manifest.dependencies
                for ((key, version) in allDeps) {
                    if (key.startsWith("org.junit.platform:")) {
                        return JUnitVersionAlignment(version, mapPlatformToJupiter(version))
                    }
                    if (key.startsWith("org.junit.jupiter:")) {
                        return JUnitVersionAlignment(mapJupiterToPlatform(version), version)
                    }
                }
            }

            return JUnitVersionAlignment(
                platformVersion = DEFAULT_PLATFORM_VERSION,
                jupiterVersion = DEFAULT_JUPITER_VERSION,
            )
        }

        fun parseJarInfo(path: Path): ParsedJarInfo? {
            if (Files.isDirectory(path)) return null
            val fileName = path.fileName.toString()
            if (!fileName.endsWith(".jar", ignoreCase = true)) return null

            val match = JAR_NAME_REGEX.matchEntire(fileName)
            if (match != null) {
                return ParsedJarInfo(match.groupValues[1], match.groupValues[2])
            }

            val parent = path.parent
            if (parent != null) {
                val versionDir = parent.fileName?.toString()
                val artifactDir = parent.parent?.fileName?.toString()
                if (versionDir != null && artifactDir != null && versionDir.firstOrNull()?.isDigit() == true) {
                    return ParsedJarInfo(artifactName = artifactDir, version = versionDir)
                }
            }

            return ParsedJarInfo(artifactName = fileName.removeSuffix(".jar"), version = null)
        }

        fun isJUnitPlatformArtifact(artifactName: String): Boolean {
            return artifactName.startsWith("junit-platform-")
        }

        fun isJUnitJupiterArtifact(artifactName: String): Boolean {
            return artifactName.startsWith("junit-jupiter")
        }

        fun alignAndDeduplicateClasspath(
            classpath: List<Path>,
            alignment: JUnitVersionAlignment,
        ): List<Path> {
            val result = mutableListOf<Path>()
            val seenArtifactNames = mutableSetOf<String>()
            val seenCanonicalPaths = mutableSetOf<Path>()

            for (path in classpath) {
                if (!Files.exists(path)) continue

                if (Files.isDirectory(path)) {
                    val canonical = path.toAbsolutePath().normalize()
                    if (seenCanonicalPaths.add(canonical)) {
                        result.add(path)
                    }
                    continue
                }

                val info = parseJarInfo(path)
                if (info != null) {
                    // Check for JUnit Platform mismatch
                    if (isJUnitPlatformArtifact(info.artifactName)) {
                        if (info.version != null && info.version != alignment.platformVersion) {
                            // Purge conflicting/unaligned JUnit Platform JAR
                            continue
                        }
                        if (!seenArtifactNames.add(info.artifactName)) {
                            // Deduplicate duplicate artifact
                            continue
                        }
                    } else if (isJUnitJupiterArtifact(info.artifactName)) {
                        if (info.version != null && info.version != alignment.jupiterVersion) {
                            // Purge conflicting/unaligned JUnit Jupiter JAR
                            continue
                        }
                        if (!seenArtifactNames.add(info.artifactName)) {
                            // Deduplicate duplicate artifact
                            continue
                        }
                    } else if (info.artifactName in setOf("opentest4j", "apiguardian-api")) {
                        if (!seenArtifactNames.add(info.artifactName)) {
                            // Deduplicate
                            continue
                        }
                    }
                }

                val canonical = path.toAbsolutePath().normalize()
                if (seenCanonicalPaths.add(canonical)) {
                    result.add(path)
                }
            }

            return result
        }

        fun findJarForClass(clazz: Class<*>): Path? {
            val location = clazz.protectionDomain?.codeSource?.location ?: return null
            return try {
                val path = Path.of(location.toURI())
                if (Files.exists(path)) path else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
