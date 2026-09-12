Create a new Git branch named:

feat/phase-4-native-build-engine

Then implement Qutivex Phase 4: Native Kotlin/JVM Build Engine.

Goal:
Remove Gradle completely from qutivex build, run, and test.

Implement:
- native source scanning
- classpath building from Qutivex dependency state
- direct Kotlin compiler invocation
- native qutivex build
- native qutivex run
- native qutivex test
- JUnit Platform execution
- JAR packaging
- incremental build fingerprints
- safe build cache

Keep all existing Phase 3.5 functionality working.

At the end:
qutivex add/remove/install/update/tree/list/build/run/test must work without Gradle for normal Kotlin/JVM projects.

Add tests, benchmarks, docs, and provide a final implementation report.
