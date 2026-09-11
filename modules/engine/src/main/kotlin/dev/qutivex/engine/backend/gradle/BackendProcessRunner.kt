package dev.qutivex.engine.backend.gradle

import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.Path

/** Interface for executing backend tasks. */
interface BackendProcessRunner {
    fun execute(
        projectDir: Path,
        tasks: List<String>,
        extraArgs: List<String> = emptyList(),
        stdout: PrintWriter,
        stderr: PrintWriter,
        stdin: InputStream? = null,
    ): Int
}
