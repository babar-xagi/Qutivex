# Architecture

## Module Boundaries

Qutivex follows a strict three-module architectural design:

```text
:cli -----> :engine -----> :core
  |                         ^
  +-------------------------+
```

| Module | Responsibility | Key Classes |
| --- | --- | --- |
| `modules/core` | Pure models, validation, lockfile spec, manifest spec, coordinate parsing (no I/O, no network) | `DependencyCoordinate`, `LockfileSpec`, `ManifestSpec`, `ProjectSpec` |
| `modules/engine` | File system mutations, background process runners, lockfile manager, dependency resolution, diagnostics | `DependencyManager`, `LockfileManager`, `ManifestWriter`, `ProjectInitializer`, `ProjectExecutor`, `GradleBackendGenerator`, `EnvironmentDiagnostics` |
| `modules/cli` | Command-line argument parsing, terminal formatting, emoji output, timing duration formatting, exit code mapping | `QutivexCli`, `MainKt` |

### Invariants & Contracts
- `core` has zero dependencies on I/O, network, or Gradle APIs. It contains pure data classes and deterministic business logic.
- `engine` executes backend tasks, manages atomic file operations, and runs processes without calling `System.exit()` or printing to terminal streams directly.
- `cli` translates all exceptions into standard exit codes (`0` for success, `1` for operation failures, `2` for syntax/usage errors).

---

## Dependency Management & Lockfile Architecture (Phase 2)

### 1. Domain Abstractions & Clean Separation
Dependency operations are decoupled into clean domain models and pluggable resolution providers:
- `DependencyResolver`: Contract for resolving project dependencies without coupling callers to Gradle APIs.
- `RepositoryClient`: Abstraction for querying remote package registries (e.g. Maven Central).
- `ArtifactCache`: Abstraction for local caching of resolved artifacts and metadata.
- `ResolvedDependency`: Pure model for a resolved component (`group`, `artifact`, `version`, `scope`, `direct`, `dependencies`, `checksum`, `repository`).
- `DependencyGraph`: Full directed dependency graph representing all direct and transitive packages.

### 2. Dependency Resolution & Atomic Staging
When `qutivex add` is executed:
1. The requested coordinate (`group:artifact:version` or `group:artifact@version`) is parsed and validated via `DependencyCoordinate`.
2. A new in-memory `ManifestSpec` is created.
3. The candidate manifest is staged to `qutivex.toml`.
4. The disposable backend under `.qutivex/gradle/` is generated.
5. The `DependencyResolver` (`GradleDependencyResolver`) executes `qutivexResolve`—a lightweight task that evaluates configuration resolution graphs directly without executing Kotlin source compilation daemon.
6. **Automatic Rollback**: If resolution fails (e.g., nonexistent coordinate or network error), `DependencyManager` immediately restores the previous valid `qutivex.toml` and regenerates the backend, leaving zero corrupted files.
7. **Lockfile Generation**: Upon successful resolution, `LockfileManager` writes `qutivex.lock` with deterministic package ordering and SHA-256 manifest integrity hash.

### 3. Comprehensive Lockfile Specification (`qutivex.lock`)
```toml
# Qutivex lockfile (version = 1) - generated automatically, do not edit manually
version = 1
manifest-hash = "c18f0a359..."

[toolchain]
kotlin = "2.4.10"
jvm = 21

[[package]]
group = "org.jetbrains.kotlinx"
artifact = "kotlinx-coroutines-core"
version = "1.10.2"
scope = "runtime"
direct = true
dependencies = ["org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm"]
checksum = "sha256:..."
repository = "https://repo.maven.apache.org/maven2/"

[[package]]
group = "org.jetbrains.kotlinx"
artifact = "kotlinx-coroutines-core-jvm"
version = "1.10.2"
scope = "runtime"
direct = false
dependencies = ["org.jetbrains.kotlin:kotlin-stdlib"]
checksum = "sha256:..."
repository = "https://repo.maven.apache.org/maven2/"
```

- **Transitive Coverage**: Every direct and transitive package is locked with its exact version, scope, direct flag, upstream dependencies, and repository.
- **Manifest Integrity Hash**: Calculated via SHA-256 over normalized manifest TOML content.
- **Frozen Validation (`--frozen`)**: Ensures `qutivex.lock` exists and that its `manifest-hash` precisely matches `qutivex.toml`. Any drift triggers an immediate exit with remediation guidance.

---

## Backend Generation & Performance Optimizations

Disposable Gradle backend files reside in `.qutivex/gradle/`:
```text
<project>/
  .qutivex/
    gradle/
      build.gradle.kts
      settings.gradle.kts
      gradle.properties
      gradlew
      gradlew.bat
      gradle/wrapper/
        gradle-wrapper.jar
        gradle-wrapper.properties
```

### High-Performance Tuning
- **Eliminated Redundant Wrapper Extraction**: Wrapper scripts and binary jars are only copied if missing.
- **Persistent Compilation Daemon**: `org.gradle.daemon=true` keeps the Kotlin compilation daemon alive in the background.
- **Build Caching**: `--build-cache` is passed to all tasks, enabling instant task execution when inputs have not changed.
- **Parallel Compilation & VFS Watching**: `org.gradle.parallel=true` and `org.gradle.vfs.watch=true` minimize change detection latency.

Warm command runs (`run`, `test`, `build`) execute in under 2 seconds.

---

## Packaging Pipeline

Qutivex distributes an enterprise-grade Windows MSI installer:
- Constructed via WiX Toolset v5 (`packaging/windows/qutivex.wxs`).
- Registers binaries in `C:\Program Files\Qutivex\bin`.
- Automatically appends installation directory to system `PATH`.
- Generates SHA-256 verification digests (`qutivex-x64.msi.sha256`).
