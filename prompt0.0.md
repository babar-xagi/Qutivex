Fix and verify the following Qutivex Phase 2 issues without breaking existing functionality.

## 1. Fix Windows Emoji / UTF-8 Output

Current Windows PowerShell output shows corrupted characters such as:

```text
Γ£¿
≡ƒôª
ΓÅ▒∩╕Å
```

Expected output should render correctly:

```text
✨ Initialized myapp in ...
🔍 Resolving dependency...
➕ Added dependency
➖ Removed dependency
📋 Dependencies
🧪 Tests passed
📦 Build completed
⏱️ Finished in 1.74s
```

Make terminal output UTF-8 safe on Windows PowerShell, Windows Terminal, CMD, Linux, and macOS.

Do not rely only on JVM encoding flags if they are insufficient.

Add automated tests where possible.

---

## 2. Fix `qutivex doctor` Alignment

Current broken output:

```text
Platform    Windows 10 amd64OK
```

Expected clean aligned output:

```text
Qutivex Environment

Qutivex     0.2.0-dev          OK
Platform    Windows 10 amd64   OK
Java        21.0.12.1          OK
JAVA_HOME   detected           OK
Gradle      managed            OK
Repository  reachable          OK
```

Use deterministic column formatting instead of manually inserting spaces.

It must also work with longer platform/version strings.

Add tests for the table formatter.

---

## 3. Hide Gradle Noise by Default

Currently commands expose internal Gradle output:

```text
> Task :compileKotlin
> Task :jar
BUILD SUCCESSFUL
Consider enabling configuration cache...
```

Qutivex should hide internal Gradle logs during successful normal execution.

Expected default UX:

```text
📦 Building myapp...
✅ Build completed in 1.74s
```

```text
🧪 Running tests...
✅ Tests passed in 1.94s
```

```text
📥 Resolving and installing dependencies...
✅ Dependencies installed in 1.80s
```

Add:

```bash
qutivex build --verbose
qutivex test --verbose
qutivex run --verbose
qutivex install --verbose
```

`--verbose` should show complete Gradle stdout/stderr.

On failure, normal mode must still show the important compiler/test/dependency error instead of hiding useful diagnostics.

---

## 4. Improve `qutivex.lock`

The current lockfile only mirrors declared dependencies.

Upgrade it so it records the complete resolved dependency graph, including transitive dependencies.

Example concept:

```toml
version = 1
manifest-hash = "..."

[[package]]
group = "org.jetbrains.kotlinx"
artifact = "kotlinx-coroutines-core"
version = "1.10.2"
scope = "runtime"
direct = true

[[package]]
group = "org.jetbrains.kotlinx"
artifact = "kotlinx-coroutines-core-jvm"
version = "1.10.2"
scope = "runtime"
direct = false
```

Where practical, also store:

```text
repository/source
artifact checksum
dependency relationships
```

Requirements:

* deterministic ordering
* identical input produces identical lockfile
* `--frozen` validates the full resolved state
* transitive dependency changes must be detectable

Add integration tests using a dependency with real transitive dependencies.

---

## 5. Reduce Dependence on Gradle for Dependency Resolution

Do not remove the Gradle backend for compilation yet.

However, begin separating dependency resolution from Gradle.

Introduce clear abstractions such as:

```text
DependencyResolver
RepositoryClient
ResolvedDependency
DependencyGraph
ArtifactCache
```

The architecture should allow Qutivex to eventually resolve Maven dependencies itself.

If implementing a complete independent Maven resolver is too large for this stabilization task, refactor the code so Gradle-backed resolution is isolated behind the resolver interface.

Do not pretend dependency resolution is Qutivex-native if Gradle is still performing it.

Document the current architecture accurately.

---

## 6. Improve First Dependency Resolution Performance

Investigate why:

```text
qutivex add org.junit.jupiter:junit-jupiter:5.12.2
```

can take around:

```text
18–20 seconds
```

while warm operations take around 1–2 seconds.

Profile at least:

```text
Gradle startup
plugin/configuration time
Maven metadata lookup
artifact download
backend generation
dependency verification
lockfile generation
```

Avoid unnecessary compilation when validating a dependency.

If `qutivex add` currently invokes `compileKotlin` only to check whether a dependency resolves, replace it with a lighter dependency-resolution task where possible.

Cache Maven metadata/artifact information appropriately.

Print timing information only in verbose/debug mode if detailed timings are noisy.

---

# Regression Requirements

All existing commands must continue working:

```bash
qutivex init
qutivex add
qutivex add --test
qutivex remove
qutivex list
qutivex install
qutivex install --frozen
qutivex install --offline
qutivex run
qutivex test
qutivex build
qutivex doctor
```

Existing rollback behavior for invalid dependencies must remain intact.

---

# Required Verification

Run the full automated test suite:

```powershell
.\gradlew.bat check
```

Then perform Windows CLI acceptance tests.

## Doctor

```powershell
qutivex doctor
```

Prove that output is aligned:

```text
Platform    Windows 10 amd64   OK
```

and NOT:

```text
Platform    Windows 10 amd64OK
```

## Emoji Rendering

Run:

```powershell
qutivex init emoji-test
qutivex list
qutivex build
```

Prove that Unicode symbols render correctly and no mojibake such as `Γ£¿` or `≡ƒ` appears.

## Quiet Build

Run:

```powershell
qutivex build
```

Prove normal output does not show Gradle task spam.

Then run:

```powershell
qutivex build --verbose
```

Prove Gradle details are visible.

## Transitive Lockfile

Run:

```powershell
qutivex add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
qutivex install
Get-Content qutivex.lock
```

Prove that transitive resolved packages are present in the lockfile.

## Frozen Mode

Run:

```powershell
qutivex install --frozen
```

Then modify `qutivex.toml` manually without updating the lockfile.

Run again:

```powershell
qutivex install --frozen
```

It must fail with a clear lockfile mismatch error.

## Invalid Dependency Rollback

Run:

```powershell
qutivex add com.fake.doesnotexist:nothing:999.0.0
```

Prove:

* resolution fails
* manifest changes are rolled back
* lockfile remains valid
* error output is concise

## Performance

Measure:

```powershell
qutivex add <uncached-valid-dependency>
qutivex add <another-valid-dependency>
qutivex install
qutivex build
qutivex build
```

Report cold and warm timings and identify where time is spent.

---

# Final Report

After implementation, provide:
2
1. Files changed
2. Root cause of the Windows emoji issue
3. Root cause of the doctor alignment issue
4. How Gradle output suppression works
5. New lockfile format
6. Whether dependency resolution is still Gradle-backed or Qutivex-native
7. Performance before vs after
8. Test results
9. Exact PowerShell commands used for verification
10. Example final terminal output

Do not mark the task complete unless the fixes are proven by automated tests and Windows CLI acceptance tests.
