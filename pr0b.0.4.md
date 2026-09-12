Fix Qutivex Phase 4 verification failures.

Primary bug:
Native JUnit test execution mixes incompatible JUnit Platform versions.

Error:
"OutputDirectoryProvider not available; probably due to unaligned versions of
junit-platform-engine and junit-platform-launcher."

Ensure the NativeTestRunner/QutivexTestWorker uses one fully aligned JUnit
Platform version and does not mix Qutivex's bundled launcher with incompatible
project engine/commons jars.

For junit-jupiter:5.12.2, launcher/engine/commons must be compatible with
JUnit Platform 1.12.2.

Build the test-worker classpath deterministically from the resolved lockfile,
remove duplicate/conflicting JUnit Platform jars, and add integration tests
that detect version conflicts.

Also fix the Windows UTF-8/emoji output regression visible in PowerShell.

Then rerun the full Phase 4 test suite and verification. Do not mark Phase 4
complete until qutivex test, build, runnable JAR, incremental rebuilds and
benchmarks all pass.
