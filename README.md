# ✨ Qutivex

> Simple, fast Kotlin package and project manager with the developer experience of Bun or uv.

Qutivex simplifies Kotlin development. It manages project initialization, dependency resolution, lockfiles, application execution, testing, and distribution packaging without requiring manual Gradle or Maven configuration.

---

## 🚀 Quick Start

### Windows Installation (MSI)

1. Download **`qutivex-x64.msi`** from the [GitHub Releases](https://github.com/babar-xagi/Qutivex/releases) page.
2. Run the installer (installs to `C:\Program Files\Qutivex\` and automatically configures your `PATH`).
3. Open a new PowerShell terminal and verify:

```powershell
qutivex --version
qutivex doctor
```

> [!TIP]
> **Prerequisites:** Only **JDK 21** (e.g. Eclipse Adoptium Temurin 21) is required on your machine. You do **not** need to install Gradle, Maven, or Kotlin separately — Qutivex manages its backend and toolchains automatically!

---

## 📦 Creating and Running a Project

### 1. Initialize a Project

```powershell
qutivex init my-app
cd my-app
```

Output:
```text
✨ Initialized my-app in D:\workspace\my-app ⏱️ (18ms)
```

Generated project structure:
```text
my-app/
├── qutivex.toml       # User-facing project & dependency manifest
├── src/
│   ├── main/kotlin/Main.kt
│   └── test/kotlin/AppTest.kt
├── .gitignore
└── README.md
```

### 2. Add Dependencies

Add any dependency from Maven Central using either `group:artifact:version` or `group:artifact@version`:

```powershell
qutivex add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
qutivex add io.ktor:ktor-client-core@3.0.0
qutivex add org.junit.jupiter:junit-jupiter:5.10.2 --test
```

Output:
```text
🔍 Resolving org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2...
➕ Added org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2 to [dependencies] ⏱️ (1.20s)
```

> [!NOTE]
> Qutivex validates resolution before modifying files. If a dependency cannot be resolved or is invalid, the operation fails and rolls back your `qutivex.toml` automatically!

### 3. List Dependencies

```powershell
qutivex list
```

Output:
```text
📋 Dependencies for my-app (0.1.0):

📦 [dependencies]
  • io.ktor:ktor-client-core:3.0.0
  • org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2

🧪 [test-dependencies]
  • org.junit.jupiter:junit-jupiter:5.10.2

⏱️ Checked in 12ms
```

### 4. Run the Application

```powershell
qutivex run
```

Forward command-line arguments to your application using `--`:

```powershell
qutivex run -- --port 8080 --mode production
```

Output:
```text
Hello from my-app!
✨ Finished in 1.45s
```

### 5. Run Tests

```powershell
qutivex test
```

Output:
```text
AppTest > test passes() PASSED
🧪 Tests passed in 890ms
```

### 6. Build Distribution

```powershell
qutivex build
```

Output:
```text
📦 Build completed in 1.15s
```
Your compiled application distribution (ZIP & TAR) is produced in `build/distributions/`.

### 7. Remove Dependencies

```powershell
qutivex remove io.ktor:ktor-client-core
```

Output:
```text
➖ Removed io.ktor:ktor-client-core ⏱️ (150ms)
```

### 8. Lockfile & CI Installation (`install`)

Qutivex automatically maintains a deterministic `qutivex.lock` file recording SHA-256 integrity hashes of your dependencies and toolchain.

In CI/CD environments, enforce strict reproducibility with `--frozen`:

```powershell
qutivex install --frozen
```

Output:
```text
📥 Resolving and installing dependencies...
✨ Dependencies locked and installed in 1.30s
```

For offline or air-gapped environments:
```powershell
qutivex install --offline
```

---

## ⚡ Performance

Qutivex is tuned for instant developer feedback:
- **Persistent Compilation Daemons**: Reuses background daemons to eliminate JVM cold-start overhead.
- **Build Cache (`--build-cache`)**: Warm builds and runs execute in under 2 seconds.
- **VFS File Watching**: Incremental changes are tracked continuously.
- **Elapsed Timing**: Every command reports exact execution times so you know where your time is spent.

---

## 🛠️ CLI Reference

| Command | Description |
| :--- | :--- |
| `qutivex init [directory]` | Create a new Kotlin/JVM project (default: current directory) |
| `qutivex run [-- args]` | Compile and run the application entry point with optional arguments |
| `qutivex test` | Compile and run unit & integration tests |
| `qutivex build` | Build release application distributions under `build/distributions/` |
| `qutivex add <coordinate> [-t\|--test]` | Add a dependency and sync lockfile (`group:artifact:version` or `@version`) |
| `qutivex remove <coordinate> [-t\|--test]` | Remove a dependency from `qutivex.toml` and update lockfile |
| `qutivex list` | Display declared runtime and test dependencies |
| `qutivex install [--frozen] [--offline]` | Download and lock dependencies (CI `--frozen`, offline cache `--offline`) |
| `qutivex doctor` | Inspect local environment, JDK 21 installation, and Maven Central connectivity |
| `qutivex --version` | Display installed Qutivex version |
| `qutivex --help` | Show general help or command-specific options (`qutivex <command> --help`) |

---

## 🏗️ Building from Source

Requirements: JDK 21.

### Run tests
```powershell
.\gradlew.bat check
```

### Build Windows MSI Installer
```powershell
.\gradlew.bat packageWindowsMsi
```
The installer will be generated at:
- `dist/qutivex-x64.msi`
- `dist/qutivex-x64.msi.sha256`

### Install locally
```powershell
.\gradlew.bat :cli:installDist
.\modules\cli\build\install\qutivex\bin\qutivex.bat --version
```

---

## 📄 License

Apache License 2.0. See [LICENSE](LICENSE) for details.
