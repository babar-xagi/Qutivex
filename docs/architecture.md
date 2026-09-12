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

## Native Dependency Engine (Phase 3.5)

In Phase 3.5, Qutivex completely removed Gradle from dependency management. All dependency operations (`add`, `remove`, `update`, `list`, `tree`, `install`) are executed by a native Kotlin engine:

```text
qutivex add/install/update
        ↓
NativeDependencyResolver
        ↓
MavenRepositoryClient (HTTP/TLS)
        ↓
PomParser & ComparableVersion
        ↓
LocalArtifactCache (~/.qutivex/cache/)
        ↓
qutivex.lock (deterministic TOML)
```

### 1. Domain Models & Version Comparison
- `MavenCoordinate`: Parsed coordinate model with relative repository path generation for POM, JAR, and metadata files.
- `ComparableVersion`: Maven-compliant version ordering supporting numeric components, qualifier tokens (`alpha`, `beta`, `rc`, `snapshot`, `final`/`ga`/`release`, `sp`), zero-padding equivalence (`1.0 == 1.0.0`), and qualifier ranking (`1.0-alpha < 1.0 < 1.0.1`).

### 2. POM & BOM Parser (`PomParser`)
Native DOM XML parser that handles:
- **Parent POM Inheritance**: Resolves and merges parent POMs transitively up the inheritance hierarchy.
- **Properties Interpolation**: Recursively interpolates `${property.name}`, `${project.version}`, `${project.groupId}`, and parent properties.
- **`dependencyManagement` & BOMs**: Imports BOM POMs (`<scope>import</scope>`, `<type>pom</type>`) and applies managed dependency versions.
- **Exclusions**: Matches and filters out `PomExclusion` rules transitively.
- **Optional Dependencies**: Suppresses `<optional>true</optional>` dependencies from transitive propagation.
- **Scopes**: Maps Maven scopes (`compile`, `runtime`, `test`, `provided`) correctly into Qutivex runtime and test dependencies.

### 3. Repository Client & Atomic Artifact Cache
- `RepositoryClient`: Streaming HTTP client with exponential backoff retries, SHA-256 checksum calculation, and atomic move (`.tmp.<uuid>` to final destination).
- `ArtifactCache`: Native cache located at `~/.qutivex/cache/` containing structured subdirectories:
  - `artifacts/`: Cached dependency JARs organized by Maven group/artifact/version.
  - `poms/`: Cached POM XML files.
  - `metadata/`: Version metadata.
  - `temp/`: Safe staging area.
- **Concurrent Download Locking**: `withLock(coordinateKey)` ensures simultaneous dependency downloads deduplicate work without race conditions.
- **Integrity Verification**: Verifies SHA-256 against `qutivex.lock` on install; rejects tampered artifacts via `ArtifactIntegrityException`.

### 4. Graph Resolver & Highest-Version Conflict Selection
- `NativeDependencyResolver`: Resolves the complete directed graph starting from direct manifest dependencies.
- **Conflict Resolution**: Highest version wins across all dependency depths, preventing version drift and runtime classpath incompatibilities.
- **Cycle Detection**: Tracks the resolution path to detect and safely terminate circular dependency graphs.

### 5. Self-Updater Architecture (`SelfUpdater`)
- Queries GitHub Releases API for latest Qutivex tags and assets.
- On Windows: downloads official `qutivex-x64.msi`, validates SHA-256 against release checksum digest, and executes `msiexec.exe /i <msi> /qb` for an in-place upgrade. Never directly overwrites `Program Files`.
- On Linux/macOS: informs users of release tarballs and release notes.

### 6. Comprehensive Lockfile Specification (`qutivex.lock`)
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
- **Offline + Frozen Guarantee**: Zero network, zero Gradle, zero manifest mutation, and zero lockfile mutation.

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
