package dev.qutivex.engine.lockfile

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

        val deps = mutableMapOf<String, String>()
        val depsTable = result.getTable("dependencies")
        if (depsTable != null) {
            for (key in depsTable.keySet()) {
                val keyPath = listOf(key)
                if (depsTable.isString(keyPath)) {
                    val value = depsTable.getString(keyPath)
                    if (value != null) {
                        deps[key] = value
                    }
                }
            }
        }

        val testDeps = mutableMapOf<String, String>()
        val testDepsTable = result.getTable("test-dependencies")
        if (testDepsTable != null) {
            for (key in testDepsTable.keySet()) {
                val keyPath = listOf(key)
                if (testDepsTable.isString(keyPath)) {
                    val value = testDepsTable.getString(keyPath)
                    if (value != null) {
                        testDeps[key] = value
                    }
                }
            }
        }

        return LockfileSpec(
            version = version,
            manifestHash = manifestHash,
            kotlinVersion = kotlinVersion,
            jvmTarget = jvmTarget,
            dependencies = deps,
            testDependencies = testDeps,
        )
    }

    fun write(projectDir: Path, manifest: ManifestSpec): LockfileSpec {
        val lockfile = projectDir.resolve("qutivex.lock").toAbsolutePath().normalize()
        val spec = LockfileSpec(
            version = 1,
            manifestHash = manifest.computeHash(),
            kotlinVersion = manifest.toolchain.kotlin,
            jvmTarget = manifest.toolchain.jvm,
            dependencies = manifest.dependencies,
            testDependencies = manifest.testDependencies,
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
        val currentHash = manifest.computeHash()
        if (spec.manifestHash != currentHash) {
            throw LockfileException(
                "Lockfile is out of sync with qutivex.toml in frozen mode.\n" +
                "Expected manifest hash: ${spec.manifestHash}\n" +
                "Current manifest hash:  $currentHash\n" +
                "Run 'qutivex install' without --frozen to reconcile dependencies."
            )
        }
    }
}
