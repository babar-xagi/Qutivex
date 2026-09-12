package dev.qutivex.engine.build

import dev.qutivex.core.lockfile.LockfileSpec
import dev.qutivex.core.manifest.ManifestSpec
import dev.qutivex.engine.dependency.ArtifactCache
import dev.qutivex.engine.dependency.LocalArtifactCache
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds compile, runtime, and test classpaths from Qutivex dependency state (qutivex.lock + artifact cache)
 * and toolchain runtime libraries.
 */
class ClasspathBuilder(
    private val artifactCache: ArtifactCache = LocalArtifactCache(),
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
        val result = LinkedHashSet<Path>()

        // 1. Main classes & resources
        val mainClasses = projectDir.resolve("build/classes/kotlin/main")
        val mainResources = projectDir.resolve("build/resources/main")
        if (Files.exists(mainClasses)) result.add(mainClasses)
        if (Files.exists(mainResources)) result.add(mainResources)

        // 2. Runtime and test dependency JARs from lockfile
        if (lockfile != null) {
            for (pkg in lockfile.packages) {
                val jar = artifactCache.findArtifact(pkg.group, pkg.artifact, pkg.version)
                if (jar != null && Files.exists(jar)) {
                    result.add(jar)
                }
            }
        }

        // 3. Kotlin stdlib and test libraries
        ensureKotlinToolchainJars(manifest, lockfile, result)
        ensureTestFrameworkJars(manifest, result)

        return result.filter { Files.exists(it) }.toList()
    }

    /**
     * Classpath used to execute JUnit platform tests.
     * Contains test classes, test resources, main classes, main resources,
     * runtime & test dependency JARs, Kotlin stdlib, and JUnit platform runner JARs.
     */
    fun buildTestRuntimeClasspath(
        projectDir: Path,
        manifest: ManifestSpec,
        lockfile: LockfileSpec?,
    ): List<Path> {
        val result = LinkedHashSet<Path>()

        // 1. Test classes & resources
        val testClasses = projectDir.resolve("build/classes/kotlin/test")
        val testResources = projectDir.resolve("build/resources/test")
        if (Files.exists(testClasses)) result.add(testClasses)
        if (Files.exists(testResources)) result.add(testResources)

        // 2. Main classes & resources
        val mainClasses = projectDir.resolve("build/classes/kotlin/main")
        val mainResources = projectDir.resolve("build/resources/main")
        if (Files.exists(mainClasses)) result.add(mainClasses)
        if (Files.exists(mainResources)) result.add(mainResources)

        // 3. Runtime & test dependencies
        if (lockfile != null) {
            for (pkg in lockfile.packages) {
                val jar = artifactCache.findArtifact(pkg.group, pkg.artifact, pkg.version)
                if (jar != null && Files.exists(jar)) {
                    result.add(jar)
                }
            }
        }

        // 4. Kotlin stdlib and test framework
        ensureKotlinToolchainJars(manifest, lockfile, result)
        ensureTestFrameworkJars(manifest, result)
        ensureJUnitRunnerJars(result)

        return result.filter { Files.exists(it) }.toList()
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
            // Fallback to bundled kotlin-stdlib from the running environment
            findJarForClass(KotlinVersion::class.java)?.let { result.add(it) }
        }

        // Annotations
        val cachedAnnotations = artifactCache.findArtifact("org.jetbrains", "annotations", "13.0")
            ?: artifactCache.findArtifact("org.jetbrains", "annotations", "23.0.0")
        if (cachedAnnotations != null && Files.exists(cachedAnnotations)) {
            result.add(cachedAnnotations)
        } else {
            try {
                Class.forName("org.jetbrains.annotations.Nullable")?.let {
                    findJarForClass(it)?.let { jar -> result.add(jar) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun ensureTestFrameworkJars(
        manifest: ManifestSpec,
        result: MutableSet<Path>,
    ) {
        val kotlinVersion = manifest.toolchain.kotlin

        // Check cache for kotlin-test and kotlin-test-junit5
        for (artifact in listOf("kotlin-test", "kotlin-test-junit5")) {
            val cached = artifactCache.findArtifact("org.jetbrains.kotlin", artifact, kotlinVersion)
            if (cached != null && Files.exists(cached)) {
                result.add(cached)
            }
        }

        // Fallback to bundled classes from running environment
        val testClassNames = listOf(
            "kotlin.test.Test",
            "kotlin.test.junit5.JUnit5Asserter",
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.Assertions",
        )
        for (className in testClassNames) {
            try {
                Class.forName(className)?.let {
                    findJarForClass(it)?.let { jar -> result.add(jar) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun ensureJUnitRunnerJars(result: MutableSet<Path>) {
        val testClasses = listOf(
            "org.junit.platform.launcher.Launcher",
            "org.junit.platform.launcher.core.LauncherFactory",
            "org.junit.platform.engine.TestEngine",
            "org.junit.platform.commons.JUnitException",
            "org.junit.jupiter.engine.JupiterTestEngine",
            "org.opentest4j.AssertionFailedError",
            "org.apiguardian.api.API",
        )

        for (className in testClasses) {
            try {
                Class.forName(className)?.let {
                    findJarForClass(it)?.let { jar -> result.add(jar) }
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
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
