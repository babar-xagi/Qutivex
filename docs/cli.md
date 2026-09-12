# CLI Specification & User Guide

Qutivex provides a modern, high-speed CLI for Kotlin/JVM projects, combining project creation, execution, testing, dependency management, and distribution packaging.

---

## Command Overview

| Command | Syntax | Description |
| :--- | :--- | :--- |
| `init` | `qutivex init [directory]` | Initialize a new project in the specified or current directory |
| `run` | `qutivex run [--verbose] [-- arguments...]` | Compile and run the project entry point, forwarding trailing arguments |
| `test` | `qutivex test [--verbose]` | Compile and execute unit & integration tests |
| `build` | `qutivex build [--verbose]` | Compile and produce release distributions under `build/distributions/` |
| `add` | `qutivex add <coordinate> [-t\|--test] [--verbose]` | Add a dependency to `qutivex.toml` and sync `qutivex.lock` natively |
| `remove` | `qutivex remove <coordinate> [-t\|--test] [--verbose]` | Remove a dependency from `qutivex.toml` and update `qutivex.lock` natively |
| `update` | `qutivex update [coordinate] [--check] [-t\|--test] [--verbose]` | Self-update Qutivex CLI or update an existing project dependency |
| `list` | `qutivex list` | Display all project dependencies and test dependencies |
| `tree` | `qutivex tree [--scope <scope>] [--depth <N>] [-v]` | Display the transitive dependency tree hierarchy from lockfile |
| `install` | `qutivex install [--frozen] [--offline] [--verbose]` | Resolve and cache dependencies natively; reconcile `qutivex.lock` |
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
Usage: qutivex run [--verbose] [-- <arguments...>]
```

- Any arguments after `--` are passed verbatim to the application's `main(args: Array<String>)` function.
- Suppresses internal build noise by default; pass `--verbose` to inspect full compilation details.
- Leverages Gradle daemon reuse and local build caching for sub-second re-executions.
- Displays total elapsed execution time (`⏱️ Finished in 1.25s`).

**Examples:**
```powershell
qutivex run
qutivex run --verbose
qutivex run -- --port 8080 --profile dev
```

---

### `qutivex test`

Compiles test sources and runs the test suite using JUnit Platform.

```text
Usage: qutivex test [--verbose]
```

- Clean output by default displaying test results (`PASSED`, `SKIPPED`, `FAILED`).
- Pass `--verbose` to print raw Gradle execution lifecycle.
- Reports total test elapsed duration (`✅ Tests passed in 850ms`).
- Returns exit code `0` on success, `1` if any test fails (diagnostics automatically printed to stderr).

**Example:**
```powershell
qutivex test
qutivex test --verbose
```

---

### `qutivex build`

Produces release application distributions.

```text
Usage: qutivex build [--verbose]
```

- Clean emoji output by default (`📦 Building...`, `✅ Build completed in 1.10s`).
- Pass `--verbose` to display full Gradle task graph and compiler outputs.
- Generates standalone distribution ZIP and TAR archives in `build/distributions/`.

**Example:**
```powershell
qutivex build
qutivex build --verbose
```

---

### `qutivex add`

Adds a Maven dependency to `qutivex.toml` and updates `qutivex.lock`.

```text
Usage: qutivex add <coordinate> [--test] [--verbose]
       qutivex add <coordinate> -t [--verbose]
```

- Supports standard colon syntax: `group:artifact:version`
- Supports npm-style `@` syntax: `group:artifact@version`
- `--test` / `-t`: Adds the dependency to `[test-dependencies]` instead of `[dependencies]`.
- `--verbose`: Prints detailed resolution output and network queries.
- **Atomic Safety**: Verifies that the dependency can be resolved against Maven Central before persisting. If resolution fails, changes are automatically rolled back.

**Examples:**
```powershell
qutivex add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
qutivex add io.ktor:ktor-client-core@3.0.0
qutivex add org.junit.jupiter:junit-jupiter:5.10.2 --test
qutivex add io.ktor:ktor-server-core:3.0.0 --verbose
```

---

### `qutivex remove`

Removes a dependency from `qutivex.toml` and regenerates `qutivex.lock`.

```text
Usage: qutivex remove <coordinate> [--test] [--verbose]
       qutivex remove <coordinate> -t [--verbose]
```

- Accepts `group:artifact` or `group:artifact:version`.
- `--test` / `-t`: Specifically removes from `[test-dependencies]`.
- `--verbose`: Displays detailed graph reconciliation during removal.

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

### `qutivex update`

Updates Qutivex CLI to the latest release, or updates an existing direct project dependency to a new version.

```text
Usage: qutivex update [--check]
       qutivex update <coordinate> [-t|--test] [-v|--verbose]
```

#### CLI Self-Update (No arguments)
- When run without a dependency coordinate, queries the official GitHub releases API for the latest Qutivex release.
- `--check`: Checks if an update is available and prints the current and latest versions without installing.
- On Windows: Downloads the official `.msi` installer, verifies its cryptographic SHA-256 digest against the release checksum, and launches the Windows Installer upgrade (`msiexec.exe /i <msi> /qb`). Never directly overwrites `Program Files`.
- On Linux / macOS: Displays the latest release package download URL and release notes.

**Examples:**
```powershell
qutivex update --check
qutivex update
```

#### Project Dependency Update
- Updates an existing direct dependency in `qutivex.toml` and reconciles `qutivex.lock` using the native dependency engine.
- If resolution fails, Qutivex rolls back `qutivex.toml` and `qutivex.lock` cleanly.
- Reports what transitive dependencies were upgraded, downgraded, added, or removed.

**Examples:**
```powershell
qutivex update org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
qutivex update io.ktor:ktor-client-core@3.0.1
qutivex update org.junit.jupiter:junit-jupiter:5.10.3 --test
```

**Output Example:**
```text
🔍 Resolving update for org.jetbrains.kotlinx:kotlinx-coroutines-core -> 1.10.2...
🔄 Updated org.jetbrains.kotlinx:kotlinx-coroutines-core: 1.10.1 -> 1.10.2 in [dependencies] ⏱️ (1.75s)
Transitive changes:
  • org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm: 1.10.1 -> 1.10.2 (upgraded)
```

---

### `qutivex tree`

Renders the project's transitive dependency tree directly from `qutivex.lock` in sub-second time without querying the network or Gradle backend.

```text
Usage: qutivex tree [--scope <runtime|test|all>] [--depth <N>] [-v|--verbose]
```

- `--scope <scope>`: Limit tree to `runtime`, `test`, or `all` (default: `all`).
- `--depth <N>`: Limit tree traversal depth to `N` levels.
- `-v, --verbose`: Display checksum digests and repository origin URLs.
- Identifies circular or repeated dependencies with `(*)` to prevent redundant subtree expansion.

**Examples:**
```powershell
qutivex tree
qutivex tree --scope runtime
qutivex tree --depth 2
qutivex tree --verbose
```

**Output Example:**
```text
my-service v0.1.0 (D:\projects\my-service)
├── [dependencies]
│   └── org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
│       └── org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2
│           ├── org.jetbrains.kotlin:kotlin-stdlib:2.4.10
│           │   └── org.jetbrains:annotations:23.0.0
│           ├── org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.10.2 (*)
│           └── org.jetbrains:annotations:23.0.0 (*)
└── [test-dependencies]
    └── org.junit.jupiter:junit-jupiter:5.10.2
        ├── org.junit.jupiter:junit-jupiter-api:5.10.2
        └── org.junit.jupiter:junit-jupiter-params:5.10.2
```

---

### `qutivex install`

Resolves and caches project dependencies, reconciling `qutivex.lock` using the **Native Dependency Engine** (100% Gradle-free).

```text
Usage: qutivex install [--frozen] [--offline] [--verbose]
```

- `--frozen`: Enforces strict lockfile compliance (ideal for CI/CD pipelines). If `qutivex.lock` is missing or its `manifest-hash` does not match `qutivex.toml`, the command fails with an actionable error. Lockfile is never mutated.
- `--offline`: Resolves dependencies exclusively using the local cache (`~/.qutivex/cache/`) without network queries.
- `--offline --frozen`: Performs zero network requests, zero Gradle execution, zero manifest mutations, and zero lockfile mutations while verifying local artifact presence and SHA-256 integrity.
- `--verbose`: Emits detailed native resolution logs.

**Examples:**
```powershell
qutivex install
qutivex install --frozen
qutivex install --offline
qutivex install --offline --frozen
qutivex install --verbose
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
