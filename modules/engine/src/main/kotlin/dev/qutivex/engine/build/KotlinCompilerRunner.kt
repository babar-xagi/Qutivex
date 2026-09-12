package dev.qutivex.engine.build

import dev.qutivex.core.manifest.ManifestToolchain
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

data class CompilationResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
)

/**
 * Directly invokes the Kotlin compiler (K2JVMCompiler) to compile Kotlin source files
 * targeting the requested JVM target with the provided classpath.
 */
class KotlinCompilerRunner {

    fun compile(
        sources: List<ScannedFile>,
        classpath: List<Path>,
        outputDir: Path,
        toolchain: ManifestToolchain,
        verbose: Boolean = false,
    ): CompilationResult {
        if (sources.isEmpty()) {
            return CompilationResult(success = true, exitCode = 0, output = "")
        }

        Files.createDirectories(outputDir)

        val args = mutableListOf<String>()

        // 1. Destination directory
        args.add("-d")
        args.add(outputDir.toAbsolutePath().toString())

        // 2. Classpath
        if (classpath.isNotEmpty()) {
            val cpString = classpath.joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }
            args.add("-classpath")
            args.add(cpString)

            if (classpath.any { it.fileName?.toString()?.contains("kotlin-stdlib") == true }) {
                args.add("-no-stdlib")
            }
        }

        // 3. JVM Target
        args.add("-jvm-target")
        args.add(toolchain.jvm.toString())

        // 4. Verbosity
        if (verbose) {
            args.add("-verbose")
        }

        // 5. Source files
        for (src in sources) {
            args.add(src.file.toAbsolutePath().toString())
        }

        val errBytes = ByteArrayOutputStream()
        val printStream = PrintStream(errBytes, true, UTF_8)

        val exitCode = try {
            val compiler = K2JVMCompiler()
            val result = compiler.exec(printStream, *args.toTypedArray())
            result.code
        } catch (e: Throwable) {
            printStream.println("Compiler execution error: ${e.message}")
            e.printStackTrace(printStream)
            ExitCode.INTERNAL_ERROR.code
        }

        printStream.flush()
        val output = errBytes.toString(UTF_8).trim()

        return CompilationResult(
            success = exitCode == ExitCode.OK.code,
            exitCode = exitCode,
            output = output,
        )
    }
}
