package dev.qutivex.engine.build

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * Packages compiled classes, resources, and runtime dependencies into an executable JAR.
 */
class JarPackager {

    fun packageJar(
        classesDir: Path,
        resourcesDir: Path?,
        outputJar: Path,
        mainClass: String? = null,
        version: String = "1.0.0",
        runtimeJars: List<Path> = emptyList(),
    ): Path {
        val parent = outputJar.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }

        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name("Created-By")] = "Qutivex Build Engine"
        manifest.mainAttributes[Attributes.Name("Implementation-Version")] = version

        if (!mainClass.isNullOrBlank()) {
            manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        }

        val addedEntries = mutableSetOf<String>()

        JarOutputStream(BufferedOutputStream(FileOutputStream(outputJar.toFile())), manifest).use { jos ->
            // 1. Add compiled classes
            if (Files.exists(classesDir) && classesDir.isDirectory()) {
                Files.walk(classesDir).use { stream ->
                    stream.forEach { path ->
                        if (path != classesDir) {
                            val relative = path.relativeTo(classesDir).toString().replace('\\', '/')
                            if (path.isDirectory()) {
                                val dirEntry = if (relative.endsWith("/")) relative else "$relative/"
                                if (addedEntries.add(dirEntry)) {
                                    jos.putNextEntry(JarEntry(dirEntry))
                                    jos.closeEntry()
                                }
                            } else if (path.isRegularFile()) {
                                if (addedEntries.add(relative)) {
                                    val entry = JarEntry(relative)
                                    entry.time = Files.getLastModifiedTime(path).toMillis()
                                    jos.putNextEntry(entry)
                                    Files.copy(path, jos)
                                    jos.closeEntry()
                                }
                            }
                        }
                    }
                }
            }

            // 2. Add resources
            if (resourcesDir != null && Files.exists(resourcesDir) && resourcesDir.isDirectory()) {
                Files.walk(resourcesDir).use { stream ->
                    stream.forEach { path ->
                        if (path != resourcesDir) {
                            val relative = path.relativeTo(resourcesDir).toString().replace('\\', '/')
                            if (path.isDirectory()) {
                                val dirEntry = if (relative.endsWith("/")) relative else "$relative/"
                                if (addedEntries.add(dirEntry)) {
                                    jos.putNextEntry(JarEntry(dirEntry))
                                    jos.closeEntry()
                                }
                            } else if (path.isRegularFile()) {
                                if (addedEntries.add(relative)) {
                                    val entry = JarEntry(relative)
                                    entry.time = Files.getLastModifiedTime(path).toMillis()
                                    jos.putNextEntry(entry)
                                    Files.copy(path, jos)
                                    jos.closeEntry()
                                }
                            }
                        }
                    }
                }
            }

            // 3. Add runtime dependencies for a standalone runnable JAR
            for (jarPath in runtimeJars) {
                if (Files.exists(jarPath) && Files.isRegularFile(jarPath)) {
                    try {
                        JarFile(jarPath.toFile()).use { jarFile ->
                            val entries = jarFile.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                val name = entry.name
                                if (isIgnoredJarEntry(name)) continue
                                if (addedEntries.add(name)) {
                                    val newEntry = JarEntry(name)
                                    newEntry.time = entry.time
                                    jos.putNextEntry(newEntry)
                                    if (!entry.isDirectory) {
                                        jarFile.getInputStream(entry).use { input ->
                                            input.copyTo(jos)
                                        }
                                    }
                                    jos.closeEntry()
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return outputJar
    }

    private fun isIgnoredJarEntry(name: String): Boolean {
        if (name.startsWith("META-INF/")) {
            val upper = name.uppercase()
            if (upper == "META-INF/MANIFEST.MF" ||
                upper.endsWith(".SF") ||
                upper.endsWith(".DSA") ||
                upper.endsWith(".RSA") ||
                upper == "META-INF/INDEX.LIST"
            ) {
                return true
            }
        }
        return false
    }
}
