package dev.qutivex.engine.build

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

data class TestRunSummary(
    val testCount: Int,
    val passedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val durationMs: Long,
) {
    val isSuccess: Boolean get() = failedCount == 0
}

/**
 * Worker that runs JUnit Platform tests against compiled test classes.
 * Can be executed as a child JVM process or called in-process.
 */
object QutivexTestWorker {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: QutivexTestWorker <testClassesDir>")
            exitProcess(1)
        }
        val testClassesDir = Path.of(args[0])
        val summary = runTests(testClassesDir, System.out, System.err)
        exitProcess(if (summary.isSuccess) 0 else 1)
    }

    fun runTests(
        testClassesDir: Path,
        stdout: PrintStream = System.out,
        stderr: PrintStream = System.err,
    ): TestRunSummary {
        val startTime = System.currentTimeMillis()
        var testCount = 0
        var passedCount = 0
        var failedCount = 0
        var skippedCount = 0

        if (!Files.exists(testClassesDir)) {
            stdout.println("No test classes directory found at $testClassesDir")
            return TestRunSummary(0, 0, 0, 0, 0)
        }

        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClasspathRoots(setOf(testClassesDir)))
            .build()

        val launcher = LauncherFactory.create()

        val listener = object : TestExecutionListener {
            override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
                if (testIdentifier.isTest) {
                    testCount++
                    val displayName = testIdentifier.displayName
                    val legacyReportingName = testIdentifier.legacyReportingName
                    val testName = if (displayName != legacyReportingName && !displayName.contains("(")) {
                        "$legacyReportingName > $displayName"
                    } else {
                        displayName
                    }

                    when (testExecutionResult.status) {
                        TestExecutionResult.Status.SUCCESSFUL -> {
                            passedCount++
                            stdout.println("$testName PASSED")
                        }
                        TestExecutionResult.Status.FAILED -> {
                            failedCount++
                            stderr.println("$testName FAILED")
                            testExecutionResult.throwable.ifPresent { throwable ->
                                val msg = throwable.message ?: throwable.javaClass.simpleName
                                stderr.println("  FAILURE: $msg")
                                throwable.printStackTrace(stderr)
                            }
                        }
                        TestExecutionResult.Status.ABORTED -> {
                            skippedCount++
                            stdout.println("$testName SKIPPED")
                        }
                        else -> {}
                    }
                }
            }
        }

        launcher.registerTestExecutionListeners(listener)
        launcher.execute(request)

        val duration = System.currentTimeMillis() - startTime
        return TestRunSummary(
            testCount = testCount,
            passedCount = passedCount,
            failedCount = failedCount,
            skippedCount = skippedCount,
            durationMs = duration,
        )
    }
}
