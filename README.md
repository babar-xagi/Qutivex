# Qutivex

A Kotlin package and project manager aiming for a workflow as simple as Bun or uv.
Qutivex itself is written in Kotlin and initially targets Kotlin/JVM applications.

**Status: Runnable Gradle-backed preview (`0.1.0-dev`).** Help, version output,
project initialization (`init`), application execution (`run`), testing (`test`),
distribution packaging (`build`), and environment inspection (`doctor`) work today.
Dependency modification (`add`) and lockfile synchronization (`install`) are planned for Phase 2.

## Windows Installation (MSI)

### Requirements

- Windows x64
- JDK 21 (e.g., Eclipse Adoptium Temurin 21)
- Internet connection for initial dependency/build downloads

> [!NOTE]
> - You do **not** need to install Gradle.
> - You do **not** need to install Maven.
> - You do **not** need to install Kotlin separately.
> Qutivex manages its Gradle backend and Kotlin toolchain internally.

### Install

1. Download **`qutivex-x64.msi`** from the [GitHub Releases](https://github.com/babar-xagi/Qutivex/releases) page.
2. Run the installer (installs to `C:\Program Files\Qutivex\` and configures your `PATH`).
3. Open a new PowerShell or Command Prompt:

```powershell
qutivex --version
qutivex doctor
```

4. Create and run a project:

```powershell
qutivex init hello
cd hello
qutivex run
qutivex test
qutivex build
```

### Build Windows MSI from Source

To produce `qutivex-x64.msi` locally using the repository's Gradle wrapper and WiX:

```powershell
.\gradlew.bat packageWindowsMsi
```

Output artifacts:
- `dist/qutivex-x64.msi`
- `dist/qutivex-x64.msi.sha256`

## Run the CLI from source

Requirements: JDK 21 and an internet connection for the first Gradle dependency
download. The repository includes a Gradle wrapper; no separate Gradle or Kotlin
installation is required. Set `JAVA_HOME` to your JDK if Java is not on `PATH`.

The build pins Kotlin 2.4.10 and Gradle 9.5.0, within Kotlin's documented
[compatibility range](https://kotlinlang.org/docs/gradle-configure-project.html).
The wrapper verifies the Gradle distribution's SHA-256 checksum.

Windows PowerShell, from this repository:

```powershell
.\gradlew.bat :cli:run --args="--help"
.\gradlew.bat :cli:run --args="--version"
.\gradlew.bat :cli:run --args="init build/demo"
.\gradlew.bat :cli:run --args="run"
```

Linux/macOS:

```sh
sh ./gradlew :cli:run --args="--help"
sh ./gradlew :cli:run --args="init build/demo"
```

The `run` task uses the repository root as its working directory. `init` accepts a
new directory or an empty existing directory and refuses to overwrite existing work.
The demo under `build/` is disposable and is removed by a clean build.

In IntelliJ IDEA, open the repository as a Gradle project, reload Gradle after
structural changes, select JDK 21, and run the `:cli:run` Gradle task with arguments.

## Build a local command

```powershell
.\gradlew.bat :cli:installDist
.\modules\cli\build\install\qutivex\bin\qutivex.bat --help
.\modules\cli\build\install\qutivex\bin\qutivex.bat init build/another-demo
```

On Linux/macOS use `sh ./gradlew :cli:installDist` and
`./modules/cli/build/install/qutivex/bin/qutivex --help`.
Add that `bin` directory to your `PATH` to use `qutivex` from another directory.
Keep the adjacent `lib` directory with it. These launchers require Java 21 and run
without invoking Gradle. `:cli:distZip` creates an archive under
`modules/cli/build/distributions/`; a self-contained native executable is future work.

## Intended package-manager experience

This workflow is the MVP target, **not implemented yet**:

```sh
qutivex init hello
cd hello
qutivex add org.jetbrains.kotlinx:kotlinx-coroutines-core@1.10.2
qutivex run
qutivex test
qutivex build
```

The version above is an exact-version syntax example, not a latest-version claim.
Users will maintain `qutivex.toml` and commit `qutivex.lock`. Qutivex will manage a
pinned Gradle backend internally for the first MVP.

## Repository layout

```text
modules/
  cli/       command parsing, terminal output, executable entry point
  core/      project models and validation without filesystem operations
  engine/    project creation; future dependency and build adapters
docs/
  architecture.md
  cli.md
  decisions/0001-gradle-backend.md
gradle/      wrapper and dependency version catalog
.github/workflows/ci.yml
QUTIVEX_PACKAGE_MANAGER_ROADMAP.md
```

Each module keeps its tests in `src/test/kotlin`. See the [roadmap](QUTIVEX_PACKAGE_MANAGER_ROADMAP.md),
[architecture](docs/architecture.md), [command specification](docs/cli.md), and
[contributor guide](CONTRIBUTING.md).

## Verify changes

```powershell
.\gradlew.bat check :cli:installDist
```

On Linux/macOS use `sh ./gradlew check :cli:installDist`.
Tests check initialization, preservation of existing files, project-name validation,
command parsing, and exit codes. CI is configured to run on Windows, Linux, and macOS.
