🛠️ Fix and harden Qutivex v0.4.1 before starting Phase 5.

🎯 Goal:
Resolve the remaining real-machine E2E issues found during v0.4.1 verification, preserve all existing functionality, then run a full regression suite.

1. Fix `toolchain list` filtering

These should work:

qutivex toolchain list
qutivex toolchain list kotlin
qutivex toolchain list jdk

Expected:
- no argument → show all toolchains
- kotlin → show only Kotlin toolchains
- jdk → show only JDK toolchains
- invalid type → clear usage error

2. Fix `toolchain update` filtering

These should work:

qutivex toolchain update
qutivex toolchain update kotlin
qutivex toolchain update jdk

Expected:
- no argument → check all installed toolchains
- kotlin → check Kotlin only
- jdk → check JDK only
- invalid type → clear usage error

3. Fix active/default toolchain removal protection

Current bug:
An active Kotlin toolchain can still be removed.

Expected:

qutivex toolchain use kotlin 2.4.10
qutivex toolchain remove kotlin 2.4.10

must fail with a clear message.

Do the same for JDK.

A toolchain may only be removed when it is not currently active/default for the relevant context.

Also ensure stale active/default pointers are never created for toolchains that do not exist.

4. Fix `toolchain use`

`qutivex toolchain use <type> <version>` must verify that the requested managed toolchain actually exists before marking it active.

Example:

qutivex toolchain use kotlin 9.9.9

must fail instead of creating a broken active/default reference.

5. Fix project environment recreation/state synchronization

Current inconsistency:
After deleting `.qutivex/` and running `qutivex build`, output may say:

Main sources UP-TO-DATE

while:

qutivex env info

reports:

main (0 classes)

Fix build state so `.qutivex/`, compiled classes, fingerprints, native build outputs, and environment metadata remain synchronized.

If `.qutivex/` is deleted or incomplete:
- invalidate stale incremental state where required
- rebuild or correctly restore environment artifacts
- never claim sources are UP-TO-DATE when the required environment outputs are missing

6. Fix `qutivex env recreate`

`env recreate` should fully reconstruct the usable project environment from:

qutivex.toml
qutivex.lock
managed toolchains
native dependency cache

After:

qutivex env recreate
qutivex env info

the environment should already contain the correct:
- Kotlin toolchain
- JDK toolchain
- dependency graph
- classpath
- compiler options
- project state

Do not leave classpath at 0 for a project with resolved dependencies if the environment is reported READY.

7. Update `qutivex doctor`

Qutivex Phase 4 uses the native build engine for normal Kotlin/JVM workflows.

Replace outdated Gradle-focused diagnostics where appropriate.

Preferred output:

Qutivex       0.4.1                 OK
Platform      Windows 10 amd64      OK
Java          21.x                  OK
JAVA_HOME     detected              OK
Toolchains    managed               OK
Environment   isolated (.qutivex)   OK
Build Engine  native                OK
Repository    reachable             OK

Gradle should not be presented as a required normal project build backend anymore.

8. Preserve successful v0.4.1 behavior

Do not break:

✅ managed Kotlin installation
✅ managed JDK installation
✅ automatic missing Kotlin installation
✅ project-specific Kotlin versions
✅ multiple projects with different Kotlin versions
✅ native dependency resolution
✅ native build
✅ native run
✅ native test
✅ env clean
✅ env recreate
✅ dependency installation
✅ offline/frozen behavior
✅ incremental builds
✅ toolchain update
✅ Phase 3.5 functionality

9. Add regression tests

Add automated tests for:

- `toolchain list kotlin`
- `toolchain list jdk`
- `toolchain update kotlin`
- `toolchain update jdk`
- invalid toolchain type
- cannot remove active Kotlin
- cannot remove active JDK
- cannot `use` a nonexistent toolchain
- inactive toolchain can be removed
- deleting `.qutivex/` invalidates/restores build state correctly
- no false `UP-TO-DATE` when required outputs are missing
- `env recreate` restores classpath immediately
- environment class count matches real compiled outputs
- doctor reports native build engine
- Project A Kotlin 2.4.10 and Project B Kotlin 2.1.20 remain isolated
- full build/run/test regression

10. Final verification

Run:

.\gradlew.bat test
.\gradlew.bat check

Then perform real CLI E2E verification on Windows using a fresh temporary project.

Final report must include:

- root cause of each bug
- files changed
- tests added
- before/after behavior
- complete regression results
- proof active toolchains cannot be removed
- proof nonexistent toolchains cannot be activated
- proof env recreate restores classpath
- proof deleting `.qutivex/` does not leave stale incremental state
- final release-readiness verdict for Qutivex v0.4.1
