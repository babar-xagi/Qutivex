# ADR 0001: Kotlin/JVM CLI with a managed Gradle backend

Status: accepted direction; backend implementation pending.

## Problem

The first user-visible milestone must create, add a dependency, run, test, and build
a Kotlin application. Implementing POM processing, Kotlin variants, compilation,
incremental builds, and test discovery separately would delay that workflow.

## Decision

Build Qutivex as a Kotlin/JVM CLI on JDK 21. Use the repository's wrapper to build the
tool itself. For user projects, implement an engine adapter around a pinned Gradle
distribution; users interact with the Qutivex CLI and manifest. Keep generated files
under `.qutivex/gradle` and treat them as disposable. This adapter does not exist yet.

Gradle Module Metadata describes variants beyond those available in Maven POMs,
which matters when consuming Kotlin libraries. The initial backend will retain this
ecosystem behavior rather than approximate it with a recursive POM downloader.
[Gradle Module Metadata](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html)

Qutivex will own command semantics and a portable lock envelope, deriving backend
locks and verification data from it. Gradle version locking and artifact integrity
verification are separate mechanisms. Buildscript configurations need explicit locking
in addition to ordinary configurations.
[Dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html),
[dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)

The adapter must export structured resolution results, pin compiler/plugin graphs,
verify bootstrap downloads, and prove clean reconstruction from the manifest/lock.
Merely generating `build.gradle.kts` is not enough to complete this decision.

## Consequences

- The initial implementation can prioritize simple commands and useful diagnostics.
- JVM startup, Gradle bootstrap, and daemon warmup affect latency. No Bun/uv speed
  claim is justified until comparable cold/warm measurements exist.
- Gradle files require careful escaping, correct source roots, and a documented IDE
  import route. The adapter needs a pinned, tested Kotlin/Gradle/JDK compatibility set.
- The first distribution can be JVM launch scripts plus libraries. Bundled runtimes
  and native images require separate size/startup/platform evaluations.

## Alternatives and revisit criteria

A direct Kotlin compiler plus Maven Resolver backend is a future option if measured
latency remains unacceptable. It must first match the existing compatibility and
reproducibility fixtures, including variant selection and tooling dependencies.
Keep compiler/backend-specific types out of core models to make that experiment possible.

Kotlin/Native would change JVM library availability and integration work; it is not
the initial implementation target. A native launcher also does not remove the need
for a JDK to build/run Kotlin/JVM user applications.

## Product inspiration

Adopt the small set of project commands and automatic preparation before running
code. uv documents automatic lock/sync on run; Bun documents a committed lockfile.
These inspire the experience, not a claim of identical implementation or flags.
[uv project synchronization](https://docs.astral.sh/uv/concepts/projects/sync/),
[Bun lockfiles](https://bun.sh/docs/pm/lockfile)
