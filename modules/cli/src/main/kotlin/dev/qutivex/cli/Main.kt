package dev.qutivex.cli

import java.io.PrintWriter
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(
        QutivexCli().execute(
            args = args.toList(),
            workingDirectory = Path.of("").toAbsolutePath(),
            stdout = PrintWriter(System.out, true),
            stderr = PrintWriter(System.err, true),
        ),
    )
}
