package dev.qutivex.engine.lockfile

import dev.qutivex.core.dependency.DependencyGraph
import dev.qutivex.core.dependency.ResolvedDependency
import dev.qutivex.core.lockfile.LockfileSpec
import dev.qutivex.core.manifest.ManifestSpec
import org.tomlj.Toml
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class LockfileException(message: String) : RuntimeException(message)

/**
 * Manages reading, writing, and validating the versioned qutivex.lock file.
 */
class LockfileManager {
    fun read(projectDir: Path): LockfileSpec? {
        val lockfile = projectDir.resolve("qutivex.lock").toAbsolutePath().normalize()
        if (!Files.exists(lockfile)) return null

        val content = Files.readString(lockfile, UTF_8)
        val result = Toml.parse(content)
        if (result.hasErrors()) {
            val err = result.errors().first()
            throw LockfileException("Failed to parse qutivex.lock: ${err.message}")
        }

        val version = if (result.contains(listOf("version"))) {
            result.getLong(listOf("version"))?.toInt() ?: 1
        } else {
            1
        }

        if (!result.contains(listOf("manifest-hash"))) {
            throw LockfileException("qutivex.lock is missing required 'manifest-hash' field.")
        }
        val manifestHash = result.getString(listOf("manifest-hash"))
            ?: throw LockfileException("qutivex.lock is missing required 'manifest-hash' field.")

        val toolchainTable = result.getTable("toolchain")
        val kotlinVersion = toolchainTable?.getString(listOf("kotlin")) ?: "2.4.10"
        val jvmTarget = toolchainTable?.getLong(listOf("jvm"))?.toInt() ?: 21

        val backendTable = result.getTable("backend")
        val backendType = backendTable?.getString(listOf("type")) ?: "gradle"
        val backendVersion = backendTable?.getString(listOf("gradle")) ?: "9.5.0"

        val packages = mutableListOf<ResolvedDependency>()

        if (result.contains(listOf("package"))) {
            val pkgArray = result.getArray(listOf("package"))
            if (pkgArray != null) {
                for (i in 0 until pkgArray.size()) {
                    val table = pkgArray.getTable(i)
                    val group = table.getString(listOf("group")) ?: continue
                    val artifact = table.getString(listOf("artifact")) ?: continue
                    val pkgVersion = table.getString(listOf("version")) ?: continue
                    val scope = table.getString(listOf("scope")) ?: "runtime"
                    val direct = if (table.contains(listOf("direct"))) table.getBoolean(listOf("direct")) ?: true else true
                    val checksum = if (table.contains(listOf("checksum"))) table.getString(listOf("checksum")) else null
                    val repo = if (table.contains(listOf("repository"))) table.getString(listOf("repository")) ?: "https://repo.maven.apache.org/maven2/" else "https://repo.maven.apache.org/maven2/"

                    val childDeps = mutableListOf<String>()
                    val depsArr = table.getArray(listOf("dependencies"))
                    if (depsArr != null) {
                        for (d in 0 until depsArr.size()) {
                            val depStr = depsArr.getString(d)
                            if (!depStr.isNullOrBlank()) {
                                childDeps.add(depStr)
                            }
                        }
                    }

                    packages.add(
                        ResolvedDependency(
                            group = group,
                            artifact = artifact,
                            version = pkgVersion,
                            scope = scope,
                            direct = direct,
                            dependencies = childDeps,
                            checksum = checksum,
                            repository = repo,
                        )
                    )
                }
            }
        } else {
            // Backwards-compatibility for older key-value [dependencies] tables
            val depsTable = result.getTable("dependencies")
            if (depsTable != null) {
                for (key in depsTable.keySet()) {
                    val keyPath = listOf(key)
                    if (depsTable.isString(keyPath)) {
                        val ver = depsTable.getString(keyPath)
                        if (ver != null && key.contains(':')) {
                            val parts = key.split(':', limit = 2)
                            packages.add(ResolvedDependency(parts[0], parts[1], ver, "runtime", direct = true))
                        }
                    }
                }
            }
            val testDepsTable = result.getTable("test-dependencies")
            if (testDepsTable != null) {
                for (key in testDepsTable.keySet()) {
                    val keyPath = listOf(key)
                    if (testDepsTable.isString(keyPath)) {
                        val ver = testDepsTable.getString(keyPath)
                        if (ver != null && key.contains(':')) {
                            val parts = key.split(':', limit = 2)
                            packages.add(ResolvedDependency(parts[0], parts[1], ver, "test", direct = true))
                        }
                    }
                }
            }
        }

        return LockfileSpec(
            version = version,
            manifestHash = manifestHash,
            kotlinVersion = kotlinVersion,
            jvmTarget = jvmTarget,
            backendType = backendType,
            backendVersion = backendVersion,
            packages = packages,
        )
    }

    fun write(
        projectDir: Path,
        manifest: ManifestSpec,
        dependencyGraph: DependencyGraph? = null,
    ): LockfileSpec {
        val lockfile = projectDir.resolve("qutivex.lock").toAbsolutePath().normalize()

        val resolvedPackages = if (dependencyGraph != null && dependencyGraph.packages.isNotEmpty()) {
            dependencyGraph.packages
        } else {
            // Fall back to direct packages if graph wasn't passed
            val pkgs = mutableListOf<ResolvedDependency>()
            for ((coord, ver) in manifest.dependencies) {
                val parts = coord.split(':', limit = 2)
                if (parts.size == 2) {
                    pkgs.add(ResolvedDependency(parts[0], parts[1], ver, "runtime", direct = true))
                }
            }
            for ((coord, ver) in manifest.testDependencies) {
                val parts = coord.split(':', limit = 2)
                if (parts.size == 2) {
                    pkgs.add(ResolvedDependency(parts[0], parts[1], ver, "test", direct = true))
                }
            }
            pkgs
        }

        val spec = LockfileSpec(
            version = 1,
            manifestHash = manifest.computeHash(),
            kotlinVersion = manifest.toolchain.kotlin,
            jvmTarget = manifest.toolchain.jvm,
            backendType = "gradle",
            backendVersion = "9.5.0",
            packages = resolvedPackages,
        )

        val parent = lockfile.parent ?: Path.of(".")
        Files.createDirectories(parent)
        val tempFile = Files.createTempFile(parent, "qutivex-lock-", ".tmp")
        try {
            Files.writeString(tempFile, spec.toToml(), UTF_8)
            try {
                Files.move(tempFile, lockfile, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tempFile, lockfile, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
        return spec
    }

    fun verifyFrozen(projectDir: Path, manifest: ManifestSpec) {
        val lockfile = projectDir.resolve("qutivex.lock").toAbsolutePath().normalize()
        if (!Files.exists(lockfile)) {
            throw LockfileException(
                "Frozen lockfile mode requires 'qutivex.lock' to exist, but none was found. Run 'qutivex install' first."
            )
        }
        val spec = read(projectDir)
            ?: throw LockfileException("Failed to read 'qutivex.lock'.")
        // 1. Verify toolchain versions match
        if (spec.kotlinVersion != manifest.toolchain.kotlin || spec.jvmTarget != manifest.toolchain.jvm) {
            throw LockfileException(
                "Toolchain mismatch in frozen mode.\n" +
                "Expected: Kotlin ${spec.kotlinVersion}, JVM ${spec.jvmTarget}\n" +
                "Current:  Kotlin ${manifest.toolchain.kotlin}, JVM ${manifest.toolchain.jvm}\n" +
                "Run 'qutivex install' without --frozen to reconcile dependencies."
            )
        }

        // 2. Verify backend build-tool match
        if (spec.backendType != "gradle" || spec.backendVersion != "9.5.0") {
            throw LockfileException(
                "Backend build-tool mismatch in frozen mode.\n" +
                "Expected backend: ${spec.backendType} ${spec.backendVersion}\n" +
                "Current backend:  gradle 9.5.0\n" +
                "Run 'qutivex install' without --frozen to reconcile build-tool state."
            )
        }

        // 3. Verify manifest hash
        val currentHash = manifest.computeHash()
        if (spec.manifestHash != currentHash) {
            throw LockfileException(
                "Lockfile is out of sync with qutivex.toml in frozen mode.\n" +
                "Expected manifest hash: ${spec.manifestHash}\n" +
                "Current manifest hash:  $currentHash\n" +
                "Run 'qutivex install' without --frozen to reconcile dependencies."
            )
        }

        // Verify declared direct dependencies match
        val directRuntime = spec.dependencies
        val directTest = spec.testDependencies
        if (directRuntime != manifest.dependencies || directTest != manifest.testDependencies) {
            throw LockfileException(
                "Lockfile direct dependencies do not match qutivex.toml in frozen mode.\n" +
                "Run 'qutivex install' without --frozen to reconcile dependencies."
            )
        }
    }
}
