# CLI Specification & User Guide

Qutivex provides a modern, high-speed CLI for Kotlin/JVM projects, combining project creation, execution, testing, dependency management, and distribution packaging.

---

## Command Overview

| Command | Syntax | Description |
| :--- | :--- | :--- |
| `init` | `qutivex init [directory]` | Initialize a new project in the specified or current directory |
| `run` | `qutivex run [-- arguments...]` | Compile and run the project entry point, forwarding trailing arguments |
| `test` | `qutivex test` | Compile and execute unit & integration tests |
| `build` | `qutivex build` | Compile and produce release distributions under `build/distributions/` |
| `add` | `qutivex add <coordinate> [-t\|--test]` | Add a dependency to `qutivex.toml` and sync `qutivex.lock` |
| `remove` | `qutivex remove <coordinate> [-t\|--test]` | Remove a dependency from `qutivex.toml` and update `qutivex.lock` |
| `list` | `qutivex list` | Display all project dependencies and test dependencies |
| `install` | `qutivex install [--frozen] [--offline]` | Download and resolve dependencies; reconcile `qutivex.lock` |
| `doctor` | `qutivex doctor` | Inspect environment, Java 21 runtime, and Maven Central connectivity |
| `help` | `qutivex --help`, `qutivex <cmd> --help` | Display general help or command-specific options |
| `version` | `qutivex --version`, `qutivex -V` | Print current Qutivex version |

---

## Detailed Command Specifications

### `qutivex init`

Creates a new Kotlin/JVM project.

```text
Usage: qutivex init [directory]
       qutivex init -- <directory>
```

- If `[directory]` is omitted, the project is created in the current working directory.
- Refuses to overwrite any existing files or non-empty directories.
- Validates the project name against naming conventions (lowercase letters, numbers, hyphens, maximum 64 characters).

**Example:**
```powershell
qutivex init my-service
cd my-service
```

---

### `qutivex run`

Compiles and executes the application entry point specified in `qutivex.toml` (`application.main-class`).

```text
Usage: qutivex run [-- <arguments...>]
```

- Any arguments after `--` are passed verbatim to the application's `main(args: Array<String>)` function.
- Leverages Gradle daemon reuse and local build caching for sub-second re-executions.
- Displays total elapsed execution time (`✨ Finished in 1.25s`).

**Examples:**
```powershell
qutivex run
qutivex run -- --port 8080 --profile dev
```

---

### `qutivex test`

Compiles test sources and runs the test suite using JUnit Platform.

```text
Usage: qutivex test
```

- Logs test outcomes (`PASSED`, `SKIPPED`, `FAILED`).
- Reports total test elapsed duration (`🧪 Tests passed in 850ms`).
- Returns exit code `0` on success, `1` if any test fails.

**Example:**
```powershell
qutivex test
```

---

### `qutivex build`

Produces release application distributions.

```text
Usage: qutivex build
```

- Generates standalone distribution ZIP and TAR archives in `build/distributions/`.
- Reports duration (`📦 Build completed in 1.10s`).

**Example:**
```powershell
qutivex build
```

---

### `qutivex add`

Adds a Maven dependency to `qutivex.toml` and updates `qutivex.lock`.

```text
Usage: qutivex add <coordinate> [--test]
       qutivex add <coordinate> -t
```

- Supports standard colon syntax: `group:artifact:version`
- Supports npm-style `@` syntax: `group:artifact@version`
- `--test` / `-t`: Adds the dependency to `[test-dependencies]` instead of `[dependencies]`.
- **Atomic Safety**: Verifies that the dependency can be resolved against Maven Central before persisting. If resolution fails, changes are automatically rolled back.

**Examples:**
```powershell
qutivex add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
qutivex add io.ktor:ktor-client-core@3.0.0
qutivex add org.junit.jupiter:junit-jupiter:5.10.2 --test
```

---

### `qutivex remove`

Removes a dependency from `qutivex.toml` and regenerates `qutivex.lock`.

```text
Usage: qutivex remove <coordinate> [--test]
       qutivex remove <coordinate> -t
```

- Accepts `group:artifact` or `group:artifact:version`.
- `--test` / `-t`: Specifically removes from `[test-dependencies]`.

**Examples:**
```powershell
qutivex remove org.jetbrains.kotlinx:kotlinx-coroutines-core
qutivex remove org.junit.jupiter:junit-jupiter --test
```

---

### `qutivex list`

Inspects and lists all declared dependencies in `qutivex.toml`.

```text
Usage: qutivex list
```

**Output Example:**
```text
📋 Dependencies for my-service (0.1.0):

📦 [dependencies]
  • io.ktor:ktor-client-core:3.0.0
  • org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2

🧪 [test-dependencies]
  • org.junit.jupiter:junit-jupiter:5.10.2

⏱️ Checked in 14ms
```

---

### `qutivex install`

Downloads and reconciles project dependencies with `qutivex.lock`.

```text
Usage: qutivex install [--frozen] [--offline]
```

- `--frozen`: Enforces strict lockfile compliance (ideal for CI/CD pipelines). If `qutivex.lock` is missing or its `manifest-hash` does not match `qutivex.toml`, the command fails with an actionable error.
- `--offline`: Resolves dependencies exclusively using the local cache without network queries.

**Examples:**
```powershell
qutivex install
qutivex install --frozen
qutivex install --offline
```

---

### `qutivex doctor`

Inspects the local runtime environment to verify prerequisites.

```text
Usage: qutivex doctor
```

- Verifies that JDK 21 is available (via `JAVA_HOME` or `PATH`).
- Checks Maven Central reachability.
- Displays platform and architecture information.

---

## Exit Codes

| Exit Code | Meaning |
| :---: | :--- |
| `0` | Success |
| `1` | Operational failure (compilation failure, test failure, resolution error, missing manifest) |
| `2` | Usage / syntax error (invalid arguments, unknown options, missing parameters) |
