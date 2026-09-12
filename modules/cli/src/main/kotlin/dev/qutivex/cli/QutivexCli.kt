package dev.qutivex.cli

import dev.qutivex.core.dependency.DependencyCoordinate
import dev.qutivex.engine.dependency.DependencyException
import dev.qutivex.engine.dependency.DependencyManager
import dev.qutivex.engine.diagnostics.EnvironmentDiagnostics
import dev.qutivex.engine.lockfile.LockfileException
import dev.qutivex.engine.manifest.ManifestParseException
import dev.qutivex.engine.manifest.ManifestParser
import dev.qutivex.engine.project.ProjectExecutor
import dev.qutivex.engine.project.ProjectInitializer
import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import java.util.Properties

/** The command-line boundary. Application code returns exit codes instead of exiting the JVM. */
class QutivexCli(
    private val projectInitializer: ProjectInitializer = ProjectInitializer(),
    private val projectExecutor: ProjectExecutor = ProjectExecutor(),
    private val dependencyManager: DependencyManager = DependencyManager(),
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
                Command.AddHelp -> {
                    stdout.println(ADD_HELP)
                    0
                }
                Command.RemoveHelp -> {
                    stdout.println(REMOVE_HELP)
                    0
                }
                Command.ListDepsHelp -> {
                    stdout.println(LIST_HELP)
                    0
                }
                Command.InstallHelp -> {
                    stdout.println(INSTALL_HELP)
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
                    val start = System.currentTimeMillis()
                    val target = workingDirectory.resolve(command.directory).toAbsolutePath().normalize()
                    val project = projectInitializer.initialize(target)
                    val elapsed = System.currentTimeMillis() - start
                    stdout.println("✨ Initialized ${project.name} in $target ⏱️ (${formatDuration(elapsed)})")
                    0
                }
                is Command.Run -> {
                    val start = System.currentTimeMillis()
                    val code = projectExecutor.run(
                        projectDir = workingDirectory,
                        args = command.forwardArgs,
                        verbose = command.verbose,
                        stdout = stdout,
                        stderr = stderr,
                        stdin = stdin,
                    )
                    val elapsed = System.currentTimeMillis() - start
                    if (code == 0) {
                        stdout.println("⏱️ Finished in ${formatDuration(elapsed)}")
                    } else {
                        stdout.println("❌ Run failed in ${formatDuration(elapsed)}")
                    }
                    code
                }
                is Command.Test -> {
                    stdout.println("🧪 Running tests...")
                    val start = System.currentTimeMillis()
                    val code = projectExecutor.test(
                        projectDir = workingDirectory,
                        verbose = command.verbose,
                        stdout = stdout,
                        stderr = stderr,
                    )
                    val elapsed = System.currentTimeMillis() - start
                    if (code == 0) {
                        stdout.println("✅ Tests passed in ${formatDuration(elapsed)}")
                    } else {
                        stdout.println("❌ Tests failed in ${formatDuration(elapsed)}")
                    }
                    code
                }
                is Command.Build -> {
                    val projectName = try {
                        val mf = workingDirectory.resolve("qutivex.toml")
                        if (Files.exists(mf)) ManifestParser().parse(mf).project.name else "project"
                    } catch (_: Exception) {
                        "project"
                    }
                    stdout.println("📦 Building $projectName...")
                    val start = System.currentTimeMillis()
                    val code = projectExecutor.build(
                        projectDir = workingDirectory,
                        verbose = command.verbose,
                        stdout = stdout,
                        stderr = stderr,
                    )
                    val elapsed = System.currentTimeMillis() - start
                    if (code == 0) {
                        stdout.println("✅ Build completed in ${formatDuration(elapsed)}")
                    } else {
                        stdout.println("❌ Build failed in ${formatDuration(elapsed)}")
                    }
                    code
                }
                is Command.Add -> {
                    val start = System.currentTimeMillis()
                    stdout.println("🔍 Resolving ${command.coordinate.standardNotation}...")
                    dependencyManager.add(
                        projectDir = workingDirectory,
                        coordinate = command.coordinate,
                        isTest = command.isTest,
                        verbose = command.verbose,
                        stdout = stdout,
                        stderr = stderr,
                    )
                    val elapsed = System.currentTimeMillis() - start
                    val targetSection = if (command.isTest) "[test-dependencies]" else "[dependencies]"
                    stdout.println("➕ Added ${command.coordinate.standardNotation} to $targetSection ⏱️ (${formatDuration(elapsed)})")
                    0
                }
                is Command.Remove -> {
                    val start = System.currentTimeMillis()
                    dependencyManager.remove(
                        projectDir = workingDirectory,
                        coordinateKey = command.coordinateKey,
                        isTest = command.isTest,
                        verbose = command.verbose,
                    )
                    val elapsed = System.currentTimeMillis() - start
                    stdout.println("➖ Removed ${command.coordinateKey} ⏱️ (${formatDuration(elapsed)})")
                    0
                }
                Command.ListDeps -> {
                    val start = System.currentTimeMillis()
                    val manifest = dependencyManager.list(workingDirectory)
                    val elapsed = System.currentTimeMillis() - start
                    stdout.println("📋 Dependencies for ${manifest.project.name} (${manifest.project.version}):")
                    stdout.println()
                    stdout.println("📦 [dependencies]")
                    if (manifest.dependencies.isEmpty()) {
                        stdout.println("  (no dependencies)")
                    } else {
                        for ((key, version) in manifest.dependencies) {
                            stdout.println("  • $key:$version")
                        }
                    }
                    stdout.println()
                    stdout.println("🧪 [test-dependencies]")
                    if (manifest.testDependencies.isEmpty()) {
                        stdout.println("  (no dependencies)")
                    } else {
                        for ((key, version) in manifest.testDependencies) {
                            stdout.println("  • $key:$version")
                        }
                    }
                    stdout.println()
                    stdout.println("⏱️ Checked in ${formatDuration(elapsed)}")
                    0
                }
                is Command.Install -> {
                    val start = System.currentTimeMillis()
                    if (!command.offline || !command.frozen) {
                        stdout.println("📥 Resolving and installing dependencies...")
                    }
                    val code = dependencyManager.install(
                        projectDir = workingDirectory,
                        frozen = command.frozen,
                        offline = command.offline,
                        verbose = command.verbose,
                        stdout = stdout,
                        stderr = stderr,
                    )
                    val elapsed = System.currentTimeMillis() - start
                    if (code == 0) {
                        if (command.offline && command.frozen) {
                            stdout.println("✅ Dependencies verified from local cache in ${formatDuration(elapsed)}")
                        } else {
                            stdout.println("✅ Dependencies installed in ${formatDuration(elapsed)}")
                        }
                    } else {
                        stdout.println("❌ Installation failed in ${formatDuration(elapsed)}")
                    }
                    code
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
        } catch (failure: DependencyException) {
            stderr.println("error: ${failure.message}")
            1
        } catch (failure: LockfileException) {
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
            "add" -> parseAdd(args.drop(1))
            "remove" -> parseRemove(args.drop(1))
            "list" -> parseList(args.drop(1))
            "install" -> parseInstall(args.drop(1))
            "doctor" -> parseDoctor(args.drop(1))
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
        var verbose = false
        val forwardArgs: List<String>
        if (args.contains("--")) {
            val dashIndex = args.indexOf("--")
            val beforeDash = args.subList(0, dashIndex)
            forwardArgs = args.subList(dashIndex + 1, args.size)
            for (arg in beforeDash) {
                when {
                    arg in setOf("--verbose", "-v") -> verbose = true
                    arg.startsWith('-') -> throw UsageException("Unknown option for 'run': $arg")
                    else -> throw UsageException("Unexpected argument for 'run': $arg. Arguments to the application must follow '--'.")
                }
            }
        } else {
            forwardArgs = emptyList()
            for (arg in args) {
                when {
                    arg in setOf("--verbose", "-v") -> verbose = true
                    arg.startsWith('-') -> throw UsageException("Unknown option for 'run': $arg")
                    else -> throw UsageException("Unexpected argument for 'run': $arg. Use '--' to pass arguments to the application.")
                }
            }
        }
        return Command.Run(forwardArgs, verbose)
    }

    private fun parseTest(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.TestHelp
        var verbose = false
        for (arg in args) {
            when {
                arg in setOf("--help", "-h") -> return Command.TestHelp
                arg in setOf("--verbose", "-v") -> verbose = true
                arg.startsWith('-') -> throw UsageException("Unknown option for 'test': $arg")
                else -> throw UsageException("'test' does not accept positional arguments.")
            }
        }
        return Command.Test(verbose)
    }

    private fun parseBuild(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.BuildHelp
        var verbose = false
        for (arg in args) {
            when {
                arg in setOf("--help", "-h") -> return Command.BuildHelp
                arg in setOf("--verbose", "-v") -> verbose = true
                arg.startsWith('-') -> throw UsageException("Unknown option for 'build': $arg")
                else -> throw UsageException("'build' does not accept positional arguments.")
            }
        }
        return Command.Build(verbose)
    }

    private fun parseAdd(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.AddHelp
        if (args.isEmpty()) {
            throw UsageException("Missing dependency coordinate. Usage: qutivex add <coordinate> [--test] [--verbose]")
        }
        var coordinateStr: String? = null
        var isTest = false
        var verbose = false
        for (arg in args) {
            when {
                arg in setOf("--help", "-h") -> return Command.AddHelp
                arg in setOf("--test", "-t") -> isTest = true
                arg in setOf("--verbose", "-v") -> verbose = true
                arg.startsWith('-') -> throw UsageException("Unknown option for 'add': $arg")
                coordinateStr != null -> throw UsageException("'add' accepts at most one dependency coordinate.")
                else -> coordinateStr = arg
            }
        }
        if (coordinateStr == null) {
            throw UsageException("Missing dependency coordinate. Usage: qutivex add <coordinate> [--test] [--verbose]")
        }
        val coordinate = try {
            DependencyCoordinate.parse(coordinateStr)
        } catch (e: IllegalArgumentException) {
            throw UsageException(e.message ?: "Invalid dependency coordinate.")
        }
        return Command.Add(coordinate, isTest, verbose)
    }

    private fun parseRemove(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.RemoveHelp
        if (args.isEmpty()) {
            throw UsageException("Missing dependency coordinate. Usage: qutivex remove <coordinate> [--test] [--verbose]")
        }
        var coordinateStr: String? = null
        var isTest = false
        var verbose = false
        for (arg in args) {
            when {
                arg in setOf("--help", "-h") -> return Command.RemoveHelp
                arg in setOf("--test", "-t") -> isTest = true
                arg in setOf("--verbose", "-v") -> verbose = true
                arg.startsWith('-') -> throw UsageException("Unknown option for 'remove': $arg")
                coordinateStr != null -> throw UsageException("'remove' accepts at most one dependency coordinate.")
                else -> coordinateStr = arg
            }
        }
        if (coordinateStr == null) {
            throw UsageException("Missing dependency coordinate. Usage: qutivex remove <coordinate> [--test] [--verbose]")
        }
        val key = try {
            DependencyCoordinate.parseKey(coordinateStr)
        } catch (e: IllegalArgumentException) {
            throw UsageException(e.message ?: "Invalid dependency coordinate.")
        }
        return Command.Remove(key, isTest, verbose)
    }

    private fun parseList(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.ListDepsHelp
        if (args.isNotEmpty()) {
            val first = args.first()
            if (first.startsWith('-')) {
                throw UsageException("Unknown option for 'list': $first")
            }
            throw UsageException("'list' does not accept additional arguments.")
        }
        return Command.ListDeps
    }

    private fun parseInstall(args: List<String>): Command {
        if (args.size == 1 && args.first() in setOf("--help", "-h")) return Command.InstallHelp
        var frozen = false
        var offline = false
        var verbose = false
        for (arg in args) {
            when {
                arg in setOf("--help", "-h") -> return Command.InstallHelp
                arg == "--frozen" -> frozen = true
                arg == "--offline" -> offline = true
                arg in setOf("--verbose", "-v") -> verbose = true
                arg.startsWith('-') -> throw UsageException("Unknown option for 'install': $arg")
                else -> throw UsageException("Unexpected argument for 'install': $arg")
            }
        }
        return Command.Install(frozen, offline, verbose)
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
        data object AddHelp : Command
        data object RemoveHelp : Command
        data object ListDepsHelp : Command
        data object InstallHelp : Command
        data object DoctorHelp : Command
        data object Version : Command
        data class Init(val directory: String) : Command
        data class Run(val forwardArgs: List<String>, val verbose: Boolean = false) : Command
        data class Test(val verbose: Boolean = false) : Command
        data class Build(val verbose: Boolean = false) : Command
        data class Add(val coordinate: DependencyCoordinate, val isTest: Boolean, val verbose: Boolean = false) : Command
        data class Remove(val coordinateKey: String, val isTest: Boolean, val verbose: Boolean = false) : Command
        data object ListDeps : Command
        data class Install(val frozen: Boolean, val offline: Boolean, val verbose: Boolean = false) : Command
        data object Doctor : Command
    }

    private class UsageException(message: String) : IllegalArgumentException(message)

    companion object {
        internal fun formatDuration(millis: Long): String {
            return when {
                millis < 1000 -> "${millis}ms"
                millis < 60_000 -> {
                    val seconds = millis / 1000.0
                    String.format(Locale.US, "%.2fs", seconds)
                }
                else -> {
                    val minutes = millis / 60_000
                    val remainingSeconds = (millis % 60_000) / 1000.0
                    String.format(Locale.US, "%dm %.1fs", minutes, remainingSeconds)
                }
            }
        }

        val HELP = """
            ✨ Qutivex - simple, fast Kotlin project tooling

            Usage: qutivex <command> [options]

            Commands:
              init [directory]    Create a Kotlin/JVM project (default: current directory)
              run [-- args]       Run the project application entry point
              test                Run project tests
              build               Build project distributions
              add <dep>           Add a dependency to qutivex.toml
              remove <dep>        Remove a dependency from qutivex.toml
              list                List project dependencies
              install             Resolve and lock dependencies to qutivex.lock
              doctor              Inspect local environment and requirements
              help                Show this help

            Options:
              -h, --help          Show this help
              -V, --version       Show the Qutivex version

            Run 'qutivex <command> --help' for command-specific options.
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
            Usage: qutivex run [-v|--verbose] [-- <arguments...>]

            Compile and run the project application entry point.
            Arguments after '--' are forwarded to the application.

            Options:
              -v, --verbose     Show detailed Gradle execution logs
              -h, --help        Show this help

            Examples:
              qutivex run
              qutivex run --verbose
              qutivex run -- --port 8080
              qutivex run -- arg1 arg2
        """.trimIndent()

        val TEST_HELP = """
            Usage: qutivex test [-v|--verbose]

            Compile and run project tests.

            Options:
              -v, --verbose     Show complete Gradle test output and tasks
              -h, --help        Show this help
        """.trimIndent()

        val BUILD_HELP = """
            Usage: qutivex build [-v|--verbose]

            Compile and produce application distributions under build/.

            Options:
              -v, --verbose     Show complete Gradle build output and tasks
              -h, --help        Show this help
        """.trimIndent()

        val ADD_HELP = """
            Usage: qutivex add <coordinate> [-t|--test] [-v|--verbose]
                   qutivex add <coordinate> -t

            Add a Maven dependency to qutivex.toml and update qutivex.lock.
            Coordinates can be specified as 'group:artifact:version' or 'group:artifact@version'.

            Options:
              -t, --test        Add to [test-dependencies] instead of [dependencies]
              -v, --verbose     Show complete resolution output
              -h, --help        Show this help

            Examples:
              qutivex add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0
              qutivex add io.ktor:ktor-client-core@3.0.0
              qutivex add org.junit.jupiter:junit-jupiter:5.10.2 --test
        """.trimIndent()

        val REMOVE_HELP = """
            Usage: qutivex remove <coordinate> [-t|--test] [-v|--verbose]
                   qutivex remove <coordinate> -t

            Remove a dependency from qutivex.toml and update qutivex.lock.
            Coordinates can be specified as 'group:artifact' or 'group:artifact:version'.

            Options:
              -t, --test        Remove from [test-dependencies]
              -v, --verbose     Show complete resolution output
              -h, --help        Show this help

            Examples:
              qutivex remove org.jetbrains.kotlinx:kotlinx-coroutines-core
              qutivex remove org.junit.jupiter:junit-jupiter --test
        """.trimIndent()

        val LIST_HELP = """
            Usage: qutivex list

            List all dependencies and test dependencies configured in qutivex.toml.

            Options:
              -h, --help        Show this help
        """.trimIndent()

        val INSTALL_HELP = """
            Usage: qutivex install [--frozen] [--offline] [-v|--verbose]

            Resolve and download project dependencies, updating qutivex.lock.

            Options:
              --frozen          Require qutivex.lock to match qutivex.toml without modifying it (CI mode)
              --offline         Use cached dependencies without network queries
              -v, --verbose     Show complete Gradle output
              -h, --help        Show this help

            Examples:
              qutivex install
              qutivex install --frozen
              qutivex install --offline
              qutivex install --verbose
        """.trimIndent()

        val DOCTOR_HELP = """
            Usage: qutivex doctor

            Inspect local environment, Java installation, and build dependencies.

            Options:
              -h, --help        Show this help
        """.trimIndent()
    }
}
