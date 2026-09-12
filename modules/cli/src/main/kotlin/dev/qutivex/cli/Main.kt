package dev.qutivex.cli

import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Ensure UTF-8 output encoding across all operating systems and consoles
    System.setProperty("file.encoding", "UTF-8")
    System.setProperty("stdout.encoding", "UTF-8")
    System.setProperty("stderr.encoding", "UTF-8")

    // On Windows, Java 21's System.console()?.writer() writes directly to the Win32 console
    // via WriteConsoleW using native wide UTF-16 code units. This avoids code page mismatch
    // (e.g. CP437/CP1252 in PowerShell) and renders Unicode emoji characters natively.
    // When redirected or running in headless pipelines, fall back to UTF-8 OutputStreamWriter.
    val console = System.console()
    val stdout = if (console != null) {
        console.writer()
    } else {
        PrintWriter(OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)
    }
    val stderr = PrintWriter(OutputStreamWriter(System.err, StandardCharsets.UTF_8), true)

    exitProcess(
        QutivexCli().execute(
            args = args.toList(),
            workingDirectory = Path.of("").toAbsolutePath(),
            stdout = stdout,
            stderr = stderr,
        ),
    )
}
