# Changelog

All notable changes to the Qutivex project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.2.0] - 2026-09-12

### Added
- **Dependency Management Lifecycle (Phase 2)**:
  - `qutivex add <coordinate> [--test|-t]`:
    - Add runtime or test dependencies using standard `group:artifact:version` or npm-style `group:artifact@version` syntax.
    - Automatically stages changes, validates resolution against Maven Central, and rolls back atomically on failure.
  - `qutivex remove <coordinate> [--test|-t]`:
    - Safely remove dependencies from `qutivex.toml` and synchronize the lockfile.
  - `qutivex list`:
    - Display all declared runtime and test dependencies in a structured, scoped view.
  - `qutivex install [--frozen] [--offline]`:
    - Synchronize and download dependencies.
    - Support for `--frozen` (strict CI mode: ensures `qutivex.lock` matches `qutivex.toml` without modifying either).
    - Support for `--offline` (uses cached local dependencies without attempting network queries).
  - **Deterministic Lockfile (`qutivex.lock`)**:
    - Versioned TOML lockfile specification (`version = 1`).
    - Tracks SHA-256 manifest integrity hash (`manifest-hash`), pinned toolchains (`kotlin`, `jvm`), and sorted dependencies.
- **Execution Performance & Timing**:
  - Elapsed execution time reporting on all operations (`✨ Finished in 1.42s`, `⏱️ (took 120ms)`).
  - Human-friendly duration formatting supporting milliseconds, seconds, and minutes.
- **Terminal Aesthetics & Emojis**:
  - Expressive CLI feedback: `✨`, `⏱️`, `📦`, `🧪`, `➕`, `➖`, `📋`, `📥`, `🔍`.

### Changed
- **Massive Performance Optimizations**:
  - Re-copying of Gradle wrapper binaries (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) is now skipped if the files already exist.
  - Configured `gradle.properties` for maximum throughput:
    - `org.gradle.daemon=true` (keeps compilation daemon alive between commands)
    - `org.gradle.parallel=true` (concurrent task execution)
    - `org.gradle.caching=true` (reusable task build cache)
    - `org.gradle.vfs.watch=true` (virtual file system watching for sub-second change detection)
  - Added `--build-cache` to `run`, `test`, `build`, and `install` commands, reducing warm run times from ~24s down to ~1.5s–2s.
- **Help Documentation**:
  - Enhanced all command help screens (`--help`) with clear options, parameter constraints, and real-world examples.

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
