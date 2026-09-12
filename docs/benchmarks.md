# Qutivex Performance Benchmarks

This document reports reproducible execution measurements across CLI startup, bootstrap, dependency management, graph querying, and incremental compilation workflows.

Measurements were conducted on Windows 10 x64 using JDK 21 (Temurin-21.0.6) and PowerShell 7.

---

## Benchmark Summary Results

| Operation | Description | Average (ms) | Min (ms) | Max (ms) | Backend Interaction |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Startup (`--version`)** | Direct CLI launcher & version read | **355.6 ms** | 328 ms | 413 ms | None (Pure JVM) |
| **Startup (`--help`)** | Help text rendering | **291.8 ms** | 253 ms | 356 ms | None (Pure JVM) |
| **Init (`qutivex init`)** | Scaffold project, manifest & sources | **357.3 ms** | 340 ms | 374 ms | None (Pure JVM) |
| **Add (`qutivex add`)** | Add coordinate & resolve graph | **1,709 ms** | 1,709 ms | 1,709 ms | Lightweight Gradle Resolve |
| **Install (Cold)** | Download artifacts & generate lockfile | **4,048 ms** | 4,048 ms | 4,048 ms | Gradle Daemon (Resolution + Cache) |
| **Install (Warm)** | Check lockfile & reconcile state | **3,319 ms** | 2,812 ms | 4,238 ms | Gradle Daemon (Cached) |
| **Install (`--offline --frozen`)** | Local cache lookup & SHA-256 verification | **1,914 ms** | 1,841 ms | 2,063 ms | None (Bypasses Gradle entirely) |
| **Tree (`qutivex tree`)** | In-memory transitive graph rendering | **524.2 ms** | 468 ms | 586 ms | None (Read from `qutivex.lock`) |
| **Update (`qutivex update`)** | Fast check / no-op version reconciliation | **629.0 ms** | 610 ms | 640 ms | None on identical versions |
| **Run (No-change)** | Compile & execute entry point | **2,703 ms** | 2,422 ms | 2,878 ms | Gradle Daemon (UP-TO-DATE tasks) |
| **Build (No-change)** | Produce distribution archives | **2,699 ms** | 2,334 ms | 2,915 ms | Gradle Daemon (UP-TO-DATE tasks) |
| **Test (`qutivex test`)** | JUnit Platform test suite execution | **2,594 ms** | 2,195 ms | 2,992 ms | Gradle Daemon (UP-TO-DATE tasks) |

---

## Analysis & Architectural Takeaways

### 1. Pure JVM Commands (`init`, `--help`, `--version`, `tree`)
- **Latency:** ~300ms - 500ms
- **Design:** By strictly avoiding backend generation and daemon invocation for `init`, `tree`, `--help`, and `--version`, Qutivex provides sub-second terminal responsiveness for routine queries.
- `qutivex tree` reads directly from the canonical `qutivex.lock` file, parsing TOML and rendering tree branches without touching Gradle or remote Maven repositories.

### 2. Dependency Management Speed (`add`, `install --offline --frozen`)
- **`qutivex add`:** By executing an isolated `qutivexResolve` task rather than compiling the Kotlin source files, dependency addition executes in **~1.7s**, compared to ~18s in naive compiler-based approaches.
- **`install --offline --frozen`:** Completely decouples from the Gradle process runner, performing streaming cryptographic SHA-256 verification and cache location lookups in **~1.9s** with guaranteed zero network traffic.

### 3. Execution Backend Overhead (`run`, `test`, `build`)
- **Warm Re-runs:** Sub-3s execution achieved through:
  - Gradle daemon persistent memory reuse (`--daemon`)
  - Incremental build caching (`org.gradle.caching=true`)
  - Suppressed Gradle lifecycle logging, rendering only clean CLI indicators and elapsed execution times.

---

## Reproducing Benchmarks

To reproduce these benchmarks on your local machine:

```powershell
.\gradlew.bat :cli:installDist
powershell -ExecutionPolicy Bypass -File scripts\benchmark.ps1
```
