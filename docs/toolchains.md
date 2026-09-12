# Toolchain & JDK Architecture

This document explains the runtime and compiler toolchain architecture of Qutivex, clarifying the separation between the **CLI Runtime JDK** and the **Application JDK Target**.

---

## 1. Separation of Concerns

Qutivex distinguishes between two distinct JDK roles:

```text
┌────────────────────────────────────────────────────────┐
│                   CLI Runtime JDK                      │
│ • Runs the Qutivex CLI binary (qutivex, qutivex.bat)   │
│ • Executes background build daemons                    │
│ • Requirement: Java 21 or higher (LTS)                 │
└───────────────────────────┬────────────────────────────┘
                            │ manages
┌───────────────────────────▼────────────────────────────┐
│                Application Target Toolchain            │
│ • Configured in qutivex.toml ([toolchain] table)       │
│ • Bytecode target for Kotlin compiler (jvmTarget = 21) │
│ • Execution runtime for application classes & tests    │
│ • Kotlin compiler version pin (kotlin = "2.4.10")      │
└────────────────────────────────────────────────────────┘
```

---

## 2. CLI Runtime JDK

### Requirement
The Qutivex CLI is compiled for **Java 21**. Running `qutivex` requires a Java 21+ JRE or JDK available on your machine.

### Discovery & Validation
When invoked, the Qutivex launcher scripts (`qutivex` on POSIX, `qutivex.bat` on Windows) locate the Java runtime using the following precedence:
1. `JAVA_HOME` environment variable (if pointing to a valid Java installation).
2. System `PATH` (`java` executable).

On launch, the script queries `java --version`. If a Java version below 21 is detected, the CLI halts with a friendly, actionable diagnostic before launching the JVM:

```text
Detected Java: 17
Required Java: 21

Please install JDK 21 to use this version of Qutivex.
```

### Supported Vendors
Any standard Java 21+ distribution is fully supported:
- **Eclipse Adoptium Temurin** (Recommended)
- **Amazon Corretto**
- **Azul Zulu**
- **Oracle OpenJDK**
- **GraalVM JDK 21**

---

## 3. Application Target Toolchain (`qutivex.toml`)

The application's compilation and runtime target is declared in the project manifest:

```toml
[toolchain]
kotlin = "2.4.10"
jvm = 21
```

- **`kotlin`:** Pins the Kotlin compiler version used to compile application and test sources.
- **`jvm`:** Specifies the JVM bytecode target version.

### Enforcement in `--frozen` Mode
When running `qutivex install --frozen` (standard in CI/CD pipelines), Qutivex verifies that the `[toolchain]` in `qutivex.toml` strictly matches the pinned `[toolchain]` recorded in `qutivex.lock`:

```toml
[toolchain]
kotlin = "2.4.10"
jvm = 21

[backend]
type = "gradle"
gradle = "9.5.0"
```

If a developer changes the Kotlin version or JVM target in `qutivex.toml` without running `qutivex install` to reconcile the lockfile, frozen mode rejects the build:

```text
error: Toolchain mismatch in frozen mode.
Expected: Kotlin 2.4.10, JVM 21
Current:  Kotlin 2.3.0, JVM 21
Run 'qutivex install' without --frozen to reconcile dependencies.
```

---

## 4. Installing Java 21

### Windows
```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```
or via Chocolatey:
```powershell
choco install openjdk21
```

### macOS
```bash
brew install openjdk@21
```

### Linux (Ubuntu / Debian)
```bash
sudo apt update && sudo apt install -y openjdk-21-jdk
```

### Cross-Platform via SDKMAN!
```bash
sdk install java 21.0.6-tem
```
