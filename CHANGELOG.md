# Changelog

All notable changes to the Qutivex project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.3.0] - 2026-09-12

### Added
- **Phase 3 - Public Alpha Distribution & Measured Performance**:
  - **Transitive Dependency Tree (`qutivex tree`)**:
    - Sub-second in-memory ASCII tree rendering directly from `qutivex.lock`.
    - Scope filtering via `--scope runtime|test|all`.
    - Depth limiting via `--depth <N>`.
    - Verbose mode (`-v, --verbose`) displaying SHA-256 digests and repository origin URLs.
    - Duplicate and circular dependency handling using `(*)` annotations.
  - **Dependency Update Command (`qutivex update`)**:
    - Explicit version updating for runtime and test dependencies with rollback safety on resolution failure.
    - Reconciles `qutivex.lock` and surfaces transitive changes (upgrades, downgrades, additions, removals).
  - **Backend & Wrapper Bootstrap Hardening**:
    - Pinned cryptographic `distributionSha256Sum` for Gradle binary distribution wrapper.
    - Atomic write operations (`ATOMIC_MOVE`) for generated backend and wrapper files to guarantee recovery from interrupted processes.
  - **Cross-Platform Distribution & Packaging**:
    - Automated `dist/` packaging of GZIP-compressed tarballs (`.tar.gz`) and zip archives (`.zip`) alongside Windows MSI installer (`qutivex-x64.msi`).
    - POSIX installer script (`scripts/install.sh`) for Linux and macOS.
    - PowerShell installer script (`scripts/install.ps1`) for Windows.
  - **Toolchain & Benchmark Documentation**:
    - Created `docs/toolchains.md` clarifying CLI runtime JDK 21+ vs application bytecode targets.
    - Published reproducible benchmark measurements in `docs/benchmarks.md` and automated benchmark runner `scripts/benchmark.ps1`.

---

## [0.2.2] - 2026-09-12

### Added
- **Phase 2 Hardening & Integrity Enforcement**:
  - **Offline + Frozen Fast Verification**:
    - `qutivex install --offline --frozen` performs zero-network dependency verification directly from local artifact cache.
    - Reports: `✅ Dependencies verified from local cache in <duration>`.
  - **Cryptographic Artifact Integrity Verification (SHA-256)**:
    - Every cached artifact is verified against the lockfile's SHA-256 checksum on install.
    - Deliberate corruptions and cache tampering are rejected with expected vs. actual digests.
  - **Actionable Offline Missing Artifact Diagnostics**:
    - Surfaces exact coordinate and expected file location on disk rather than internal Gradle dumps.
  - **Concurrent Project Mutation Locking (`.qutivex/project.lock`)**:
    - Process-exclusive locking across mutating operations (`add`, `remove`, `install`).
    - Reports active process PID, operation name, and automatically recovers stale locks from dead processes.
  - **Compiler & Build-Tool State Locking**:
    - Locked `[toolchain]` (Kotlin, JVM) and `[backend]` (Gradle engine) validated strictly in `--frozen` mode.
  - **Security Architecture Documentation**:
    - Added `docs/security.md` covering Trust-On-First-Use (TOFU), SHA-256 integrity, repository trust, and concurrency safety.

---

## [0.2.1] - 2026-09-12

### Fixed
- **Windows Console Emoji / UTF-8 Encoding**:
  - Injected `@chcp 65001 >nul 2>&1` into `qutivex.bat` and configured explicit UTF-8 `StandardCharsets.UTF_8` wrappers in `Main.kt`.
  - Completely resolved mojibake (`Γ£¿`, `≡ƒôª`, `ΓÅ▒∩╕Å`) in Windows PowerShell, CMD, Windows Terminal, and POSIX terminals.
- **`qutivex doctor` Column Alignment**:
  - Replaced hardcoded format string width with dynamic padding in `DiagnosticTableFormatter` to prevent platform string overflow (`Platform Windows 10 amd64OK` -> `Platform    Windows 10 amd64   OK`).
- **Gradle Output Suppression & Clean CLI**:
  - Build/test/run/install commands hide internal Gradle lifecycle noise by default and show clean emoji feedback.
  - Added `--verbose` flag across `build`, `test`, `run`, `install`, `add`, and `remove` to expose underlying build tasks when needed.
  - Compiler errors and test failures surface actionable diagnostic errors in standard output streams.
- **Comprehensive Transitive Lockfile (`qutivex.lock`)**:
  - Versioned TOML lockfile specification (`version = 1`) with deterministic ordering.
  - Stores the complete resolved dependency graph via `[[package]]` entries: `group`, `artifact`, `version`, `scope`, `direct`, `dependencies`, `checksum` (SHA-256), and `repository`.
- **Domain Dependency Abstractions**:
  - Clean separation of resolution interfaces: `DependencyResolver`, `RepositoryClient`, `ArtifactCache`, `ResolvedDependency`, `DependencyGraph`.
- **Fast Dependency Resolution**:
  - Replaced slow compilation checks during `add` with lightweight `qutivexResolve` task that analyzes configuration graphs directly without executing the Kotlin compilation daemon, dropping resolution time from ~18s to ~2s.

---

## [0.2.0] - 2026-09-12

### Added
- **Dependency Management Lifecycle (Phase 2)**:
  - `qutivex add <coordinate> [--test|-t]`: Add runtime or test dependencies using `group:artifact:version` or `group:artifact@version`.
  - `qutivex remove <coordinate> [--test|-t]`: Safely remove dependencies and synchronize lockfile.
  - `qutivex list`: Display declared runtime and test dependencies in a structured, scoped view.
  - `qutivex install [--frozen] [--offline]`: Synchronize and download dependencies with `--frozen` CI validation.
  - Initial lockfile implementation and rollback safety.
- **Execution Performance & Timing**:
  - Elapsed execution time reporting on all operations (`✨ Finished in 1.42s`, `⏱️ (took 120ms)`).
- **Terminal Aesthetics & Emojis**:
  - Expressive CLI feedback: `✨`, `⏱️`, `📦`, `🧪`, `➕`, `➖`, `📋`, `📥`, `🔍`.

---

## [0.1.0] - 2026-09-12

### Added
- **Core CLI Commands**:
  - `qutivex init [directory]`: Create a fresh Kotlin/JVM project with standard directory layout and `qutivex.toml`.
  - `qutivex run [-- args]`: Compile and run the application entry point, forwarding command-line arguments.
  - `qutivex test`: Execute unit and integration tests with JUnit Platform.
  - `qutivex build`: Produce distribution archives under `build/distributions/`.
  - `qutivex doctor`: Inspect JDK 21 environment, `JAVA_HOME`, PATH, and Maven Central connectivity.
- **Disposable Backend Generator**:
  - Pinned Gradle 9.5.0 wrapper and Kotlin 2.4.10 compiler generated under `.qutivex/gradle/`.
- **Windows Packaging Pipeline**:
  - Automated WiX-based Windows MSI installer (`qutivex-x64.msi`) configured for per-machine installation and system `PATH` registration.
  - Checksum generator producing `qutivex-x64.msi.sha256`.
- **Automated CI/CD**:
  - GitHub Actions workflow running full test suites and MSI builds across Windows, Linux, and macOS.
