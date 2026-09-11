# Qutivex Windows MSI Packaging and Release

Implement a production-ready Windows x64 installer and release workflow for the current Qutivex CLI.

The final Windows installer artifact must be named:

```text
qutivex-x64.msi
```

## Current Architecture

Qutivex is currently a Kotlin/JVM CLI with a Gradle-backed execution backend.

The project already supports:

```text
qutivex init
qutivex run
qutivex test
qutivex build
```

The CLI currently generates a disposable Gradle backend internally and uses a pinned Gradle wrapper.

Do not redesign the package manager architecture as part of this task.

This task is specifically about packaging, installation, environment validation, and release distribution.

---

# User Experience Requirement

For this phase, the Windows user should only need:

```text
1. Qutivex
2. JDK 21
```

The user must NOT need to manually install:

```text
Gradle
Maven
Kotlin compiler
Kotlin CLI
IntelliJ IDEA
Android Studio
```

Qutivex must continue to manage its Gradle backend internally.

The intended installation flow is:

```text
Install JDK 21
        ↓
Install qutivex-x64.msi
        ↓
Open a new terminal
        ↓
qutivex --version
        ↓
qutivex init hello
        ↓
qutivex run
```

---

# 1. Build the Windows Distribution

Use the existing Gradle application distribution as the basis for the Windows package.

The current project can already produce:

```powershell
.\gradlew.bat :cli:installDist
```

Create a reproducible packaging pipeline that takes the generated Qutivex CLI distribution and packages it into:

```text
qutivex-x64.msi
```

Do not require Gradle to be installed globally.

Use the repository's Gradle wrapper for all build operations.

---

# 2. MSI Installation Layout

Install Qutivex into an appropriate Windows application directory, preferably:

```text
C:\Program Files\Qutivex\
```

Suggested structure:

```text
C:\Program Files\Qutivex\
├── bin\
│   ├── qutivex.bat
│   └── any required launcher files
│
├── lib\
│   ├── qutivex CLI JARs
│   └── runtime dependencies
│
└── VERSION
```

The installer must include everything required to launch the Qutivex CLI except the JDK.

---

# 3. PATH Integration

The MSI must configure Windows so that after installation the user can open a new PowerShell or Command Prompt and run:

```powershell
qutivex --version
```

from any directory.

The user must not manually edit the Windows PATH.

Ensure installation adds the correct Qutivex `bin` directory to PATH.

Uninstallation must cleanly remove the Qutivex PATH entry.

Do not remove or modify unrelated PATH entries.

---

# 4. JDK 21 Detection

For the current release, JDK 21 is the only external runtime requirement.

Qutivex should detect Java using sensible Windows discovery mechanisms such as:

```text
JAVA_HOME
java.exe available through PATH
```

If necessary, support additional reliable Windows JDK discovery mechanisms.

Validate that the discovered Java installation is usable.

Validate its major version.

The minimum supported Java version for this phase should match the project's current Java 21 toolchain requirement.

If Java is missing, do not crash with a stack trace.

Display a clear message such as:

```text
Qutivex requires JDK 21.

No compatible JDK installation was found.

Install JDK 21 and ensure either:
- JAVA_HOME points to the JDK, or
- java.exe is available on PATH.

Then run Qutivex again.
```

If an unsupported Java version is found, show the detected version and the required version.

Example:

```text
Detected Java: 17
Required Java: 21

Please install JDK 21 to use this version of Qutivex.
```

---

# 5. Do Not Require External Gradle

The user must not need:

```text
gradle
gradlew
```

installed globally.

Qutivex already generates and uses its own pinned Gradle wrapper backend.

Preserve this behavior.

A user should be able to run:

```powershell
qutivex run
```

on a Qutivex project even when:

```powershell
gradle --version
```

is unavailable.

---

# 6. Do Not Require Maven

Maven must not be an installation prerequisite.

The user should not need:

```powershell
mvn
```

Qutivex's generated Gradle backend should resolve dependencies from Maven Central itself.

---

# 7. Do Not Require a Separate Kotlin Installation

The user must not install:

```text
kotlinc
kotlin
Kotlin SDK
```

manually.

The pinned Kotlin Gradle plugin/backend must provide the Kotlin build environment required by Qutivex projects.

Verify that:

```powershell
kotlinc
```

can be absent while:

```powershell
qutivex build
qutivex run
qutivex test
```

continue to work.

---

# 8. Installer Metadata

The MSI should contain appropriate metadata:

```text
Product Name: Qutivex
Architecture: x64
Publisher: Qutivex
Version: derived from the project version
Artifact: qutivex-x64.msi
```

Do not duplicate the version manually across multiple files if it can be sourced from the project's canonical version.

---

# 9. Upgrade Behavior

Support clean upgrades.

Installing a newer Qutivex version should update the existing installation rather than create unrelated duplicate installations.

Preserve user projects and user Qutivex cache/configuration.

Never delete:

```text
user projects
~/.qutivex
dependency caches
user configuration
```

during a normal upgrade.

---

# 10. Uninstallation

Windows Settings / Installed Apps must allow Qutivex to be uninstalled.

Uninstallation should remove:

```text
Qutivex installation files
Qutivex PATH integration
installer-owned configuration
```

Do not remove user-created projects.

Do not automatically delete the user's package cache unless explicitly requested by the user.

---

# 11. Version Command

After MSI installation, this must work:

```powershell
qutivex --version
```

Expected format:

```text
Qutivex <version>
```

Example:

```text
Qutivex 0.1.0
```

---

# 12. Doctor Command / Environment Diagnostics

If a `doctor` command does not already exist, introduce a minimal environment diagnostic command:

```powershell
qutivex doctor
```

Example output:

```text
Qutivex Environment

Qutivex     0.1.0       OK
Platform    Windows x64 OK
Java        21.x        OK
JAVA_HOME   detected    OK
Gradle      managed     OK
Repository  reachable   OK
```

If Java is missing:

```text
Java        not found   ERROR
```

The command should return a non-zero exit code when a mandatory requirement is missing.

Do not require global Gradle or Maven in the doctor checks.

They should be reported as internally managed or irrelevant.

---

# 13. Acceptance Tests

Add automated or scripted Windows acceptance tests covering at least:

### Installation

Install:

```text
qutivex-x64.msi
```

Verify:

```powershell
qutivex --version
```

works from a new terminal.

### No Global Gradle

Verify global Gradle is unavailable.

Then run:

```powershell
qutivex init hello
cd hello
qutivex build
```

The build must succeed.

### No Maven

Verify Maven is unavailable.

Qutivex must still work.

### No Standalone Kotlin Compiler

Verify `kotlinc` is unavailable.

Qutivex must still build and run Kotlin projects.

### JDK 21

With JDK 21 available:

```powershell
qutivex run
qutivex test
qutivex build
```

must succeed.

### Missing Java

Temporarily execute Qutivex in an environment where Java cannot be discovered.

Qutivex must produce a clean actionable error instead of an exception stack trace.

### Directory With Spaces

Verify installation and project execution work with paths containing spaces.

Example:

```text
C:\Users\Test User\My Kotlin Projects\Hello App
```

---

# 14. Build Command for Release Artifact

Create a simple documented release command or Gradle task.

Prefer something conceptually like:

```powershell
.\gradlew.bat clean check :cli:installDist packageWindowsMsi
```

The result should appear in a predictable release directory such as:

```text
dist\
└── qutivex-x64.msi
```

The exact Gradle task name may differ if a better architecture fits the repository.

---

# 15. Checksums

Generate a SHA-256 checksum alongside the installer:

```text
qutivex-x64.msi
qutivex-x64.msi.sha256
```

The checksum file should contain the SHA-256 digest of the exact release artifact.

---

# 16. GitHub Release Publishing

Create a release workflow suitable for GitHub Releases.

For a version tag such as:

```text
v0.1.0
```

the CI pipeline should:

1. Check out the repository.
2. Configure the required build JDK.
3. Run all unit tests.
4. Run integration tests.
5. Build the CLI distribution.
6. Build `qutivex-x64.msi`.
7. Generate its SHA-256 checksum.
8. Upload both files as release artifacts.
9. Attach them to the corresponding GitHub Release.

Release assets:

```text
qutivex-x64.msi
qutivex-x64.msi.sha256
```

Do not publish a release if tests fail.

Use repository secrets only where required.

Do not hardcode credentials.

---

# 17. Documentation

Update the README with a Windows installation section.

Example:

## Windows

### Requirements

```text
Windows x64
JDK 21
Internet connection for the first dependency/build downloads
```

### Install

Download:

```text
qutivex-x64.msi
```

Run the installer.

Open a new terminal:

```powershell
qutivex --version
```

Create a project:

```powershell
qutivex init hello
cd hello
qutivex run
```

Explicitly document:

```text
You do not need to install Gradle.
You do not need to install Maven.
You do not need to install Kotlin separately.
```

---

# 18. Important Architectural Constraint

For this phase:

**Do not bundle an entire JDK into the Qutivex MSI.**

The current product requirement is:

```text
Qutivex MSI + JDK 21
```

Keep the MSI focused on installing Qutivex itself.

A future Qutivex release may introduce managed or bundled JDK toolchains, but that is outside the scope of this task.

---

# 19. Expected Final User Experience

A completely new Windows user should be able to do:

```text
1. Install JDK 21
2. Install qutivex-x64.msi
3. Open PowerShell
```

Then:

```powershell
qutivex --version
qutivex init hello
cd hello
qutivex run
qutivex test
qutivex build
```

No other development tool should need to be installed manually.

---

# Definition of Done

The task is complete only when:

* `qutivex-x64.msi` is produced successfully.
* The MSI installs on Windows x64.
* `qutivex` works globally from a new terminal.
* JDK 21 is detected correctly.
* Missing/incompatible Java produces helpful errors.
* Gradle is not globally required.
* Maven is not required.
* Standalone Kotlin is not required.
* Existing `run`, `test`, and `build` behavior continues to work.
* Unit/integration tests pass.
* MSI uninstall works correctly.
* Upgrade behavior is tested.
* SHA-256 checksum is generated.
* GitHub release workflow is implemented.
* README installation documentation is updated.
* The release pipeline produces `qutivex-x64.msi` reproducibly.
