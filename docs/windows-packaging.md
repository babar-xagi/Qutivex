# Windows Packaging and Installer Architecture

This document describes the Windows x64 MSI packaging pipeline, installation layout,
runtime requirements, and release workflow for Qutivex.

## Product and Installer Contract

| Attribute | Specification |
| --- | --- |
| Package format | Windows Installer (`.msi`) |
| Package artifact | `dist/qutivex-x64.msi` |
| Digest artifact | `dist/qutivex-x64.msi.sha256` |
| Target architecture | x64 (64-bit Windows) |
| Installation directory | `C:\Program Files\Qutivex\` |
| PATH integration | `C:\Program Files\Qutivex\bin\` added to system `PATH` |
| External runtime requirement | JDK 21 |
| Build tools required by user | None (Gradle and Kotlin compiler are managed internally) |

## Installation Layout

```text
C:\Program Files\Qutivex\
├── bin\
│   └── qutivex.bat              # Validates JDK 21 and launches CLI
├── lib\
│   ├── cli-*.jar                # Qutivex CLI application
│   ├── engine-*.jar             # Execution engine
│   ├── core-*.jar               # Domain models and validation
│   └── *.jar                    # Kotlin runtime and dependencies
└── VERSION                      # Canonical version identifier
```

## PATH Integration & Lifecycle

The MSI packages configure the system `PATH` environment variable:
- **Install:** Appends `[INSTALLFOLDER]bin` to system `PATH` for all users.
- **Uninstall:** Cleanly removes `[INSTALLFOLDER]bin` from `PATH` without modifying unrelated entries.
- **Upgrades:** Seamlessly upgrades existing files in place while preserving user projects and caches (`~/.qutivex`).

## Runtime Requirements & JDK 21 Discovery

Qutivex projects are compiled and executed using a managed Gradle backend running on JDK 21.

When `qutivex` is launched:
1. It verifies that `JAVA_HOME` or a valid `java.exe` is available.
2. It validates that the detected Java installation is **Java 21**.
3. If Java is missing or incompatible, it prints an actionable diagnostic message and exits with status `1`:

```text
Qutivex requires JDK 21.

No compatible JDK installation was found.

Install JDK 21 and ensure either:
- JAVA_HOME points to the JDK, or
- java.exe is available on PATH.

Then run Qutivex again.
```

If an unsupported Java version is found (e.g. Java 17):
```text
Detected Java: 17
Required Java: 21

Please install JDK 21 to use this version of Qutivex.
```

Users can run `qutivex doctor` at any time to inspect their environment:
```powershell
qutivex doctor
```

## Building the MSI Package Locally

Requirements:
- Windows x64
- JDK 21
- [.NET SDK](https://dotnet.microsoft.com/) (to run WiX toolset)

To build the application distribution and package the Windows installer:

```powershell
.\gradlew.bat clean check :cli:installDist packageWindowsMsi
```

Or run the standalone packaging script:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/package-msi.ps1
```

Generated outputs:
- `dist/qutivex-x64.msi`
- `dist/qutivex-x64.msi.sha256`
