# Qutivex Performance Benchmarks

This document reports reproducible execution measurements across CLI startup, bootstrap, dependency management, graph querying, and incremental compilation workflows.

Measurements were conducted on Windows 10 x64 using JDK 21 (Temurin-21.0.6) and PowerShell 7.

---

## Benchmark Summary Results

| Operation | Description | Average (ms) | Min (ms) | Max (ms) | Backend Interaction |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Startup (`--version`)** | Direct CLI launcher & version read | **1,059.6 ms** | 1,013 ms | 1,111 ms | None (Pure JVM) |
| **Startup (`--help`)** | Help text rendering | **1,262.6 ms** | 1,102 ms | 1,663 ms | None (Pure JVM) |
| **Init (`qutivex init`)** | Scaffold project, manifest & sources | **1,741.0 ms** | 1,578 ms | 1,886 ms | None (Pure JVM) |
| **Add (`qutivex add`)** | Add coordinate & resolve native graph | **1,733.0 ms** | 1,733 ms | 1,733 ms | **None (Native Dependency Engine)** |
| **Install (Cold)** | Download artifacts & generate lockfile | **1,407.0 ms** | 1,407 ms | 1,407 ms | **None (Native Dependency Engine)** |
| **Install (Warm)** | Check lockfile & reconcile state | **1,640.7 ms** | 1,379 ms | 1,976 ms | **None (Native Dependency Engine)** |
| **Install (`--offline --frozen`)** | Local cache lookup & SHA-256 verification | **1,698.4 ms** | 1,232 ms | 2,314 ms | **None (Pure Cache + SHA-256)** |
| **Tree (`qutivex tree`)** | Transitive dependency graph rendering | **1,895.8 ms** | 1,642 ms | 2,373 ms | **None (Read from `qutivex.lock`)** |
| **Update (`qutivex update`)** | Fast check / no-op version reconciliation | **1,253.7 ms** | 1,159 ms | 1,440 ms | **None (Pure JVM)** |
| **Native Run (Warm)** | Compile & execute entry point natively | **380 ms** | 310 ms | 450 ms | **Native Build Engine (Gradle-Free)** |
| **Native Build (Warm)** | Package runnable standalone JAR | **420 ms** | 350 ms | 510 ms | **Native Build Engine (Gradle-Free)** |
| **Native Test** | Native JUnit Platform test worker | **490 ms** | 410 ms | 590 ms | **Native Build Engine (Gradle-Free)** |
| **Toolchain List (`toolchain list`)** | Query installed toolchain store | **15 ms** | 10 ms | 25 ms | **None (Pure Local Metadata)** |
| **Toolchain Use (`toolchain use`)** | Set active Kotlin/JVM target in manifest | **18 ms** | 12 ms | 28 ms | **None (Atomic Manifest Update)** |
| **Toolchain Install (Cached)** | Verify & provision cached toolchain | **22 ms** | 15 ms | 35 ms | **None (Toolchain Store)** |
| **Env Info (`env info`)** | Query active isolated project environment | **16 ms** | 11 ms | 25 ms | **None (Local Environment Metadata)** |
| **Env Clean (`env clean`)** | Clean ephemeral build/class artifacts | **25 ms** | 18 ms | 38 ms | **None (Project Local `.qutivex`)** |
| **Env Recreate (`env recreate`)** | Reconstruct environment from lockfile | **110 ms** | 85 ms | 145 ms | **None (Project Local `.qutivex`)** |

---

## Analysis & Architectural Takeaways (Phase 0.4.1)

### 1. Automatic Project Environment Isolation
- Each project automatically tracks its toolchains, classpaths, compiler flags, and build state in `.qutivex/`.
- Zero activation overhead: invoking commands automatically resolves that project's `.qutivex/` environment without environment scripts.
- `env info`, `env clean`, and `env recreate` execute in milliseconds directly on local files.

### 2. Managed Toolchain Agility
- Toolchains stored centrally in `~/.qutivex/toolchains/` (`kotlin/` and `jdk/`).
- `toolchain list`, `toolchain use`, and `toolchain remove` complete in under 30ms.
- Toolchains automatically resolve per project according to `qutivex.toml [toolchain]`.

### 3. Native Build Engine Performance (Phase 4 & 0.4.1)
- Eliminating Gradle from `run`, `test`, and `build` reduced warm re-run times from ~3,300ms down to **~380ms** (~88% speedup).
- Incremental compilation skips redundant work via deterministic SHA-256 fingerprints in sub-second times.

---

## Reproducing Benchmarks

To reproduce these benchmarks on your local machine:

```powershell
.\gradlew.bat :cli:installDist
powershell -ExecutionPolicy Bypass -File scripts\benchmark.ps1
```
