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

---

## Native Kotlin/JVM Build Engine (Phase 4)

In Phase 4, Qutivex completely eliminated Gradle from normal Kotlin/JVM `build`, `run`, and `test` workflows:

```text
Developer / CLI
       ↓
NativeBuildEngine
       ↓
├── SourceScanner (main/test sources & resources)
├── ClasspathBuilder (lockfile + artifact cache + toolchain)
├── IncrementalBuildManager (SHA-256 build fingerprints)
├── KotlinCompilerRunner (direct in-process K2JVMCompiler)
├── NativeTestRunner (JUnit Platform Launcher)
└── JarPackager (runnable standalone JAR packaging)
```

### 1. Native Source Scanning (`SourceScanner`)
- Discovers `.kt`, `.kts`, and `.java` source files under `src/main/kotlin` and `src/test/kotlin`.
- Discovers resource files under `src/main/resources` and `src/test/resources` while preserving relative directory hierarchy.
- Computes SHA-256 digests and file metadata for input fingerprinting.

### 2. Classpath Resolution (`ClasspathBuilder`)
- **Compile Classpath**: Runtime dependencies from `qutivex.lock` + toolchain `kotlin-stdlib` and core annotations.
- **Runtime Classpath**: `build/classes/kotlin/main` + `build/resources/main` + runtime dependencies + `kotlin-stdlib`.
- **Test Compile Classpath**: Main classes + runtime & test dependencies + `kotlin-stdlib` + `kotlin-test` / JUnit Jupiter.
- **Test Runtime Classpath**: Test classes + test resources + main classes & resources + all dependencies + JUnit Platform engine & launcher JARs.

### 3. Direct Kotlin Compilation (`KotlinCompilerRunner`)
- Directly executes `org.jetbrains.kotlin.cli.jvm.K2JVMCompiler` in-process.
- Configures JVM target, output directories, complete resolved classpaths, and `-no-stdlib` when stdlib is supplied explicitly.
- Formats and captures compiler diagnostics cleanly with zero process startup overhead.

### 4. Incremental Build Cache (`IncrementalBuildManager`)
- Calculates a deterministic SHA-256 fingerprint over all build inputs:
  - Source files (paths, sizes, modification timestamps, SHA-256 content hashes).
  - Resource files (paths, sizes, timestamps, hashes).
  - Classpath dependency JARs (file sizes and timestamps).
  - Toolchain configuration (Kotlin compiler version, JVM target).
- Saved atomically at `build/.qutivex-<scope>-fingerprint`.
- If inputs match and compiled class files exist, compilation is skipped (`UP-TO-DATE`), yielding sub-second turnaround times.

### 5. Native Test Runner (`NativeTestRunner` & `QutivexTestWorker`)
- Executes unit and integration tests using the JUnit Platform Launcher.
- Spawns an isolated test worker process (`QutivexTestWorker`) with the complete test classpath.
- Streams live test events (`PASSED`, `FAILED`, `SKIPPED`) in real-time.
- Captures failure diagnostics and returns proper process exit codes without Gradle test task overhead.

### 6. Standalone Runnable JAR Packaging (`JarPackager`)
- Assembles compiled classes, resources, and bundled runtime dependencies into `build/libs/<project>-<version>.jar`.
- Sets `Main-Class` in `META-INF/MANIFEST.MF` based on `[application] main-class` in `qutivex.toml`.
- Produces self-contained runnable JARs executable via `java -jar <jar>`.

---

## Legacy Gradle Backend (Fallback)

For legacy or transitional environments, Gradle backend generation remains available under `.qutivex/gradle/`:
```text
<project>/
  .qutivex/
    gradle/
      build.gradle.kts
      settings.gradle.kts
      gradle.properties
```

Normal Kotlin/JVM projects no longer invoke or require Gradle.

---

## Packaging Pipeline

Qutivex distributes an enterprise-grade Windows MSI installer:
- Constructed via WiX Toolset v5 (`packaging/windows/qutivex.wxs`).
- Registers binaries in `C:\Program Files\Qutivex\bin`.
- Automatically appends installation directory to system `PATH`.
- Generates SHA-256 verification digests (`qutivex-x64.msi.sha256`).
