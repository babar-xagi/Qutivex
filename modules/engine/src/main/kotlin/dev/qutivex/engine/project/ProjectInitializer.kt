package dev.qutivex.engine.project

import dev.qutivex.core.project.ProjectSpec
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Creates a project scaffold without downloading dependencies or invoking build tools. */
class ProjectInitializer {
    fun initialize(target: Path): ProjectSpec {
        val directory = target.toAbsolutePath().normalize()
        val directoryName = directory.fileName?.toString()
            ?: throw IllegalArgumentException("Choose a project directory instead of a filesystem root.")
        val project = ProjectSpec(name = ProjectSpec.normalizeName(directoryName))

        ensureEmptyOrMissing(directory)

        try {
            Files.createDirectories(directory)
            writeNew(directory.resolve("qutivex.toml"), manifest(project))
            Files.createDirectories(directory.resolve("src/main/kotlin"))
            Files.createDirectories(directory.resolve("src/test/kotlin"))
            writeNew(directory.resolve("src/main/kotlin/Main.kt"), mainSource(project))
            writeNew(directory.resolve(".gitignore"), gitignore())
            writeNew(directory.resolve("README.md"), readme(project))
        } catch (error: IOException) {
            throw IOException(
                "Could not initialize '$directory': ${error.message}. " +
                    "Any files created were left in place; inspect the directory before retrying.",
                error,
            )
        }

        return project
    }

    private fun ensureEmptyOrMissing(directory: Path) {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return

        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw FileAlreadyExistsException(
                directory.toString(), null, "Project target must be a new or empty directory.",
            )
        }
        Files.newDirectoryStream(directory).use { entries ->
            if (entries.iterator().hasNext()) {
                throw FileAlreadyExistsException(
                    directory.toString(), null, "Project directory is not empty. Choose a new or empty directory.",
                )
            }
        }
    }

    private fun writeNew(path: Path, content: String) {
        Files.writeString(path, content, UTF_8, CREATE_NEW, WRITE)
    }

    private fun manifest(project: ProjectSpec): String = """
        schema-version = 1

        [project]
        name = "${project.name}"
        version = "${project.version}"

        [toolchain]
        kotlin = "${project.kotlinVersion}"
        jvm = ${project.jvmVersion}

        [application]
        main-class = "${project.mainClass}"

        [dependencies]

        [test-dependencies]
    """.trimIndent() + "\n"

    private fun mainSource(project: ProjectSpec): String = """
        fun main() {
            println("Hello from ${project.name}!")
        }
    """.trimIndent() + "\n"

    private fun gitignore(): String = """
        .qutivex/
        build/
        *.class
        .idea/
        *.iml
    """.trimIndent() + "\n"

    private fun readme(project: ProjectSpec): String = """
        # ${project.name}

        A Kotlin/JVM project scaffold created with Qutivex.

        - Project and dependency configuration: `qutivex.toml`
        - Application entry point: `src/main/kotlin/Main.kt`
        - Test sources: `src/test/kotlin/`
        - Requested toolchain: Kotlin ${project.kotlinVersion}, JVM ${project.jvmVersion}

        Qutivex currently supports project initialization. Dependency management and
        the `add`, `install`, `run`, `test`, and `build` commands are planned and are not
        implemented yet. This scaffold does not install a toolchain or generate a
        Gradle build. It will become runnable with Qutivex as execution support lands.
    """.trimIndent() + "\n"
}
