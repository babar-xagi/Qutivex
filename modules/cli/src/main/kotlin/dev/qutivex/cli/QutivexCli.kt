package dev.qutivex.cli

import dev.qutivex.engine.diagnostics.EnvironmentDiagnostics
import dev.qutivex.engine.manifest.ManifestParseException
import dev.qutivex.engine.project.ProjectExecutor
import dev.qutivex.engine.project.ProjectInitializer
import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Properties

/** The command-line boundary. Application code returns exit codes instead of exiting the JVM. */
class QutivexCli(
    private val projectInitializer: ProjectInitializer = ProjectInitializer(),
    private val projectExecutor: ProjectExecutor = ProjectExecutor(),
    private val diagnostics: EnvironmentDiagnostics = EnvironmentDiagnostics(),
) {
    fun execute(
        args: List<String>,
        workingDirectory: Path,
        stdout: PrintWriter,
        stderr: PrintWriter,
        stdin: InputStream = System.`in`,
    ): Int {
        val exitCode = try {
            when (val command = parse(args)) {
                Command.Help -> {
                    stdout.println(HELP)
                    0
                }
                Command.InitHelp -> {
                    stdout.println(INIT_HELP)
                    0
                }
                Command.RunHelp -> {
                    stdout.println(RUN_HELP)
                    0
                }
                Command.TestHelp -> {
                    stdout.println(TEST_HELP)
                    0
                }
                Command.BuildHelp -> {
                    stdout.println(BUILD_HELP)
                    0
                }
                Command.DoctorHelp -> {
                    stdout.println(DOCTOR_HELP)
                    0
                }
                Command.Version -> {
                    stdout.println("Qutivex ${readVersion()}")
                    0
                }
                is Command.Init -> {
                    val target = workingDirectory.resolve(command.directory).toAbsolutePath().normalize()
                    val project = projectInitializer.initialize(target)
                    stdout.println("Initialized ${project.name} in $target")
                    0
                }
                is Command.Run -> {
                    projectExecutor.run(
                        projectDir = workingDirectory,
                        args = command.forwardArgs,
                        stdout = stdout,
                        stderr = stderr,
                        stdin = stdin,
                    )
                }
                Command.Test -> {
                    projectExecutor.test(
                        projectDir = workingDirectory,
                        stdout = stdout,
                        stderr = stderr,
                    )
                }
                Command.Build -> {
                    projectExecutor.build(
                        projectDir = workingDirectory,
                        stdout = stdout,
                        stderr = stderr,
                    )
                }
                Command.Doctor -> {
                    diagnostics.printReport(diagnostics.inspect(readVersion()), stdout, stderr)
                }
            }
        } catch (failure: UsageException) {
            stderr.println("error: ${failure.message}")
            stderr.println("Run 'qutivex --help' for usage.")
            2
        } catch (failure: InvalidPathException) {
            stderr.println("error: Invalid directory path: ${failure.input}")
            2
        } catch (failure: ManifestParseException) {
            stderr.println("error: ${failure.message}")
            1
        } catch (failure: Exception) {
            stderr.println("error: ${failure.message?.takeIf { it.isNotBlank() } ?: "The operation failed."}")
            1
        }
        stdout.flush()
        stderr.flush()
        return exitCode
    }

    private fun parse(args: List<String>): Command {
        if (args.isEmpty()) return Command.Help
        return when (val first = args.first()) {
            "help", "--help", "-h" -> {
                requireNoExtraArguments(args)
                Command.Help
            }
            "--version", "-V" -> {
                requireNoExtraArguments(args)
                Command.Version
            }
            "init" -> parseInit(args.drop(1))
            "run" -> parseRun(args.drop(1))
            "test" -> parseTest(args.drop(1))
            "build" -> parseBuild(args.drop(1))
            "doctor" -> parseDoctor(args.drop(1))
            "add", "install" ->
                throw UsageException("The '$first' command is planned and is not implemented yet.")
            else -> throw UsageException("Unknown ${if (first.startsWith('-')) "option" else "command"}: $first")
        }
    }

    private fun requireNoExtraArguments(args: List<String>) {
        if (args.size != 1) throw UsageException("'${args.first()}' does not accept additional arguments.")
    }

    private fun parseInit(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.InitHelp
        var directory: String? = null
        var optionsEnded = false
        for (argument in args) {
            if (!optionsEnded && argument == "--") {
                optionsEnded = true
            } else {
                if (!optionsEnded && argument.startsWith('-')) {
                    throw UsageException("Unknown option for 'init': $argument")
                }
                if (directory != null) throw UsageException("'init' accepts at most one directory.")
                if (argument.isBlank()) throw UsageException("The directory must not be blank.")
                directory = argument
            }
        }
        return Command.Init(directory ?: ".")
    }

    private fun parseRun(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.RunHelp
        if (args.contains("--")) {
            val dashIndex = args.indexOf("--")
            val beforeDash = args.subList(0, dashIndex)
            val afterDash = args.subList(dashIndex + 1, args.size)
            for (arg in beforeDash) {
                if (arg.startsWith('-')) {
                    throw UsageException("Unknown option for 'run': $arg")
                }
                throw UsageException("Unexpected argument for 'run': $arg. Arguments to the application must follow '--'.")
            }
            return Command.Run(afterDash)
        }
        if (args.isNotEmpty()) {
            val first = args.first()
            if (first.startsWith('-')) {
                throw UsageException("Unknown option for 'run': $first")
            }
            throw UsageException("Unexpected argument for 'run': $first. Use '--' to pass arguments to the application.")
        }
        return Command.Run(emptyList())
    }

    private fun parseTest(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.TestHelp
        if (args.isNotEmpty()) {
            val first = args.first()
            if (first.startsWith('-')) {
                throw UsageException("Unknown option for 'test': $first")
            }
            throw UsageException("'test' does not accept additional arguments.")
        }
        return Command.Test
    }

    private fun parseBuild(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.BuildHelp
        if (args.isNotEmpty()) {
            val first = args.first()
            if (first.startsWith('-')) {
                throw UsageException("Unknown option for 'build': $first")
            }
            throw UsageException("'build' does not accept additional arguments.")
        }
        return Command.Build
    }

    private fun parseDoctor(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.DoctorHelp
        if (args.isNotEmpty()) {
            val first = args.first()
            if (first.startsWith('-')) {
                throw UsageException("Unknown option for 'doctor': $first")
            }
            throw UsageException("'doctor' does not accept additional arguments.")
        }
        return Command.Doctor
    }

    private fun readVersion(): String {
        val metadata = Properties()
        val resource = QutivexCli::class.java.getResourceAsStream("/qutivex-version.properties")
            ?: error("Qutivex version metadata is unavailable.")
        resource.use(metadata::load)
        return metadata.getProperty("version")?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Qutivex version metadata is invalid.")
    }

    private sealed interface Command {
        data object Help : Command
        data object InitHelp : Command
        data object RunHelp : Command
        data object TestHelp : Command
        data object BuildHelp : Command
        data object DoctorHelp : Command
        data object Version : Command
        data class Init(val directory: String) : Command
        data class Run(val forwardArgs: List<String>) : Command
        data object Test : Command
        data object Build : Command
        data object Doctor : Command
    }

    private class UsageException(message: String) : IllegalArgumentException(message)

    private companion object {
        val HELP = """
            Qutivex - simple Kotlin project tooling

            Usage: qutivex <command> [options]

            Commands:
              init [directory]  Create a Kotlin/JVM project (default: current directory)
              run [-- args]     Run the project application entry point
              test              Run project tests
              build             Build project distributions
              doctor            Inspect local environment and requirements
              help              Show this help

            Options:
              -h, --help        Show this help
              -V, --version     Show the Qutivex version

            Run 'qutivex <command> --help' for command-specific options.
            Planned commands (not implemented yet): add, install.
        """.trimIndent()

        val INIT_HELP = """
            Usage: qutivex init [directory]
                   qutivex init -- <directory>

            Create a Kotlin/JVM project in an empty or new directory.
            The directory defaults to the current working directory.
            Existing project files are never overwritten.

            Options:
              -h, --help        Show this help
              --                Treat following arguments as directory names

            Examples:
              qutivex init hello
              qutivex init "my app"
              qutivex init -- hello
        """.trimIndent()

        val RUN_HELP = """
            Usage: qutivex run [-- <arguments...>]

            Compile and run the project application entry point.
            Arguments after '--' are forwarded to the application.

            Options:
              -h, --help    Show this help

            Examples:
              qutivex run
              qutivex run -- --port 8080
              qutivex run -- arg1 arg2
        """.trimIndent()

        val TEST_HELP = """
            Usage: qutivex test

            Compile and run project tests.

            Options:
              -h, --help    Show this help
        """.trimIndent()

        val BUILD_HELP = """
            Usage: qutivex build

            Compile and produce application distributions under build/.

            Options:
              -h, --help    Show this help
        """.trimIndent()

        val DOCTOR_HELP = """
            Usage: qutivex doctor

            Inspect local environment, Java installation, and build dependencies.

            Options:
              -h, --help    Show this help
        """.trimIndent()
    }
}
