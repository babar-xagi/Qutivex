# Qutivex Revised Canonical Roadmap

> Qutivex is evolving from a Kotlin package-manager UX over Gradle into an independent Kotlin/JVM package manager and build system.

## Current Status

| Phase | Milestone | Status |
|---|---|---|
| Phase 0 | CLI foundation and project scaffold | ✅ Complete |
| Phase 1 | Runnable Gradle-backed Kotlin/JVM preview | ✅ Complete |
| Phase 2 | Reproducible package lifecycle MVP | ✅ Complete |
| Phase 3 | Public alpha, tree/update, distribution, benchmarks | ✅ Complete |
| Phase 3.5 | Native dependency engine; remove Gradle from package resolution | ✅ Complete |
| **Phase 4** | **Native Kotlin/JVM build engine; remove Gradle from build/run/test** | ✅ **Complete** |
| Phase 5 | IDE workflow, daily development, stable 1.0 hardening | ⏭️ Next |
| Phase 6 | Android, KMP, workspaces, plugins, publishing and other long-term tracks | ⏳ Future |

---

# Product Direction

Target user workflow:

```text
qutivex init
qutivex add
qutivex remove
qutivex install
qutivex update
qutivex tree
qutivex run
qutivex test
qutivex build
```

Long-term architecture:

```text
Developer
   ↓
Qutivex CLI
   ↓
qutivex.toml
   ↓
Qutivex Dependency Engine
   ↓
Qutivex Artifact Cache
   ↓
Qutivex Build Engine
   ↓
Kotlin Compiler
   ↓
JVM
```

Gradle removal happens in two controlled steps:

```text
Phase 3.5 → remove Gradle from dependency management
Phase 4   → remove Gradle from build / run / test
```

---

# Phase 0 — Product Contract and CLI Foundation ✅ COMPLETE

## Delivered

- `qutivex --help`
- `qutivex --version`
- `qutivex init [directory]`
- standard Kotlin/JVM source layout
- `qutivex.toml`
- project-name validation
- safe initialization
- overwrite protection
- modules:
  - `:core`
  - `:engine`
  - `:cli`

## Status

✅ Complete

---

# Phase 1 — Runnable Gradle-Backed Preview ✅ COMPLETE

## Delivered

- strict manifest/schema parsing
- generated `.qutivex/gradle/` backend
- managed Gradle backend
- `qutivex run`
- `qutivex test`
- `qutivex build`
- main-class support
- argument forwarding
- quiet mode
- `--verbose`
- real Maven dependencies
- transitive resolution
- JDK discovery
- Windows path support

## Architecture

```text
Qutivex
   ↓
Generated Gradle Backend
   ↓
Kotlin Compiler
   ↓
JVM
```

## Status

✅ Complete

---

# Phase 2 — Reproducible Package Lifecycle MVP ✅ COMPLETE

## Delivered Commands

```text
qutivex add
qutivex add --test
qutivex remove
qutivex remove --test
qutivex install
qutivex list
```

## Lockfile

Implemented `qutivex.lock` with:

- manifest fingerprint
- direct dependencies
- transitive dependencies
- runtime/test scopes
- repository identity
- SHA-256 checksums
- Kotlin toolchain state
- JVM target
- backend identity/version

## Reproducibility

```text
qutivex install --frozen
qutivex install --offline
qutivex install --offline --frozen
```

## Hardening

- atomic writes
- failed-operation rollback
- project mutation locking
- stale lock recovery
- artifact integrity verification
- tampered JAR rejection
- frozen manifest mismatch rejection
- toolchain mismatch rejection
- backend mismatch rejection
- offline cache verification
- TOFU security model
- MSI checksum generation

## Minor Non-Blocking UX Polish

- duplicate add should not print both `already present` and `Added`
- normal resolver errors should hide Gradle internals unless `--verbose`

## Status

✅ Complete

---

# Phase 3 — Public Alpha Distribution and Measured Performance ✅ COMPLETE

## Delivered

### Dependency Tree

```text
qutivex tree
```

Supports:

- `--scope runtime`
- `--scope test`
- `--scope all`
- `--depth <N>`
- `--verbose`
- duplicate/cycle annotations
- lockfile-only rendering
- no network/Gradle requirement for tree

### Dependency Update

```text
qutivex update <dependency>
```

Supports:

- explicit version update
- runtime/test detection
- rollback on failure
- transitive change tracking
- upgraded/downgraded/added/removed reporting

### Distribution

- Windows x64 MSI
- MSI SHA-256
- ZIP
- TAR.GZ
- Windows installer script
- POSIX installer script
- PATH integration

### Bootstrap Hardening

- pinned Gradle distribution
- distribution SHA-256
- atomic backend generation

### Benchmarks

Representative Phase 3 Windows results:

```text
qutivex tree                       ~0.5s
qutivex update (no-op)             ~0.6s
qutivex add                        ~1.7s
qutivex install --offline --frozen ~1.9s
qutivex run                        ~2.7s
qutivex test                       ~2.6s
qutivex build                      ~2.7s
```

### Release

`v0.3.0`

## Status

✅ Complete

---

# Phase 3.5 — Native Dependency Engine ✅ COMPLETE

## Milestone

Remove Gradle completely from:

- dependency resolution
- POM/metadata handling
- dependency graph construction
- artifact downloads
- artifact cache ownership
- package installation
- dependency updates

Gradle may temporarily remain only for `run`, `test`, and `build` until Phase 4.

---

## 3.5.1 — Architecture Target

Current:

```text
qutivex add/install/update
        ↓
GradleDependencyResolver
        ↓
Gradle cache
        ↓
Maven Central
```

Target:

```text
qutivex add/install/update
        ↓
QutivexDependencyResolver
        ↓
RepositoryClient
        ↓
POM / Maven Metadata Parser
        ↓
Dependency Graph Resolver
        ↓
Qutivex Artifact Cache
        ↓
qutivex.lock
```

No Gradle process may be started by dependency-only commands.

---

## 3.5.2 — Native Repository Client

Initial repository:

```text
https://repo.maven.apache.org/maven2/
```

Implement:

- HTTPS requests
- POM downloads
- JAR downloads
- Maven metadata XML
- streaming downloads
- redirects
- timeout handling
- retry for transient failures
- cancellation
- HTTP status validation
- temp-file downloads
- SHA-256
- atomic move into cache

Partial downloads must never be considered valid.

---

## 3.5.3 — Maven Coordinate Model

Create a strong domain model:

```kotlin
data class MavenCoordinate(
    val group: String,
    val artifact: String,
    val version: String
)
```

Keep exact direct versions as the Phase 3.5 policy.

Primary syntax:

```text
group:artifact:version
```

Do not introduce implicit latest/caret/dynamic ranges yet.

---

## 3.5.4 — Native POM Parser

Support:

- `groupId`
- `artifactId`
- `version`
- `packaging`
- parent POM
- properties
- `${...}` interpolation
- dependencies
- scopes
- optional dependencies
- exclusions
- dependencyManagement
- imported BOMs

Support inherited:

- group
- version
- properties
- dependencyManagement

Resolve common properties:

```text
${project.version}
${project.groupId}
${revision}
${kotlin.version}
```

Detect property cycles.

---

## 3.5.5 — Parent POM Resolution

Support:

```text
child
 ↓
parent
 ↓
grandparent
```

Requirements:

- recursive inheritance
- cached parent POMs
- deterministic merging
- parent-cycle detection

---

## 3.5.6 — BOM and dependencyManagement

Support:

- `packaging = pom`
- `dependencyManagement`
- `scope = import`
- BOM-provided dependency versions

Examples:

```text
kotlinx-coroutines-bom
junit-bom
```

POM-only modules must not require a JAR.

---

## 3.5.7 — Maven Scope Semantics

Understand at least:

```text
compile
runtime
test
provided
import
```

Qutivex must maintain:

```text
runtime graph
test graph
```

Production must not receive test-only dependencies.

Test graph may extend runtime dependencies.

Scope propagation rules must be documented and tested.

---

## 3.5.8 — Optional Dependencies

Respect:

```xml
<optional>true</optional>
```

Optional transitives do not automatically propagate downstream.

Explicit user declarations remain valid.

---

## 3.5.9 — Exclusions

Support Maven exclusions.

Example:

```text
A
└── B
    └── C
```

If A excludes C through B:

```text
A
└── B
```

C must not be resolved through that path.

---

## 3.5.10 — Conflict Resolution

Conflict policy must be deterministic.

Example:

```text
A → C:1.0
B → C:2.0
```

Track:

- requested versions
- selected version
- request paths
- selection reason

Initial policy may use highest selected version if compatibility tests support it.

The policy must be documented and tested.

---

## 3.5.11 — Dependency Graph Model

Create:

```text
ResolvedGraph
├── roots
├── modules
├── edges
├── selectedVersions
└── artifacts
```

Each module should know:

- coordinate
- scope
- direct/transitive status
- dependencies
- repository
- packaging
- artifact path
- checksum
- selection reason

Resolver logic must not depend on CLI classes.

---

## 3.5.12 — Cycle Detection

Prevent infinite loops:

```text
A → B
B → C
C → A
```

Use explicit traversal state such as:

```text
unvisited
visiting
resolved
```

---

## 3.5.13 — Qutivex Native Artifact Cache

Primary cache becomes:

```text
~/.qutivex/cache/
```

Suggested layout:

```text
~/.qutivex/cache/
├── metadata/
├── poms/
├── artifacts/
└── temp/
```

Requirements:

- deterministic lookup
- SHA-256
- atomic writes
- corruption detection
- safe concurrent access
- no valid partial artifacts

Gradle cache must no longer be the primary package cache.

---

## 3.5.14 — Download Deduplication

If multiple graph paths request the same artifact:

```text
download once
reuse many times
```

Use per-artifact locks, file locks, futures, or equivalent.

---

## 3.5.15 — Native `qutivex install`

Flow:

```text
qutivex.toml
    ↓
native resolver
    ↓
POM graph
    ↓
artifact downloads
    ↓
SHA-256
    ↓
Qutivex cache
    ↓
qutivex.lock
```

No Gradle dependency task.

---

## 3.5.16 — Native `qutivex add`

Flow:

```text
parse coordinate
    ↓
candidate manifest
    ↓
native resolve
    ↓
download/verify
    ↓
candidate lockfile
    ↓
atomic commit
```

On failure:

```text
rollback manifest
rollback lockfile
```

---

## 3.5.17 — Native `qutivex remove`

Flow:

```text
candidate manifest
    ↓
native resolution
    ↓
new reachable graph
    ↓
new lockfile
    ↓
atomic commit
```

Unused transitives must disappear.

---

## 3.5.18 — Native Dependency Update

Preserve:

```text
qutivex update group:artifact:version
```

Use native resolution and continue reporting:

- upgraded
- downgraded
- added
- removed

Rollback on resolution failure.

---

## 3.5.19 — Tree and List

Keep:

```text
qutivex tree
qutivex list
```

fully Gradle-free.

---

## 3.5.20 — Offline Mode

```text
qutivex install --offline
```

Must use only:

```text
~/.qutivex/cache/
```

Requirements:

- zero network
- zero Gradle
- precise missing-POM diagnostics
- precise missing-artifact diagnostics

---

## 3.5.21 — Frozen Mode

`qutivex install --frozen` must preserve:

- manifest/lock agreement
- immutable lockfile
- immutable manifest
- SHA-256 verification
- toolchain/backend rules

`qutivex install --offline --frozen` requires:

```text
ZERO network
ZERO Gradle
ZERO manifest mutation
ZERO lockfile mutation
```

---

## 3.5.22 — Lockfile Compatibility

Keep current lock schema compatible where possible.

Example:

```toml
[[package]]
group = "org.jetbrains"
artifact = "annotations"
version = "23.0.0"
scope = "runtime"
direct = false
checksum = "sha256:..."
repository = "https://repo.maven.apache.org/maven2/"
```

POM-only/BOM nodes may have no JAR checksum.

Lock output must be deterministic.

---

## 3.5.23 — Gradle Removal Boundary

At Phase 3.5 completion these must be Gradle-free:

```text
qutivex add
qutivex add --test
qutivex remove
qutivex remove --test
qutivex install
qutivex install --offline
qutivex install --frozen
qutivex install --offline --frozen
qutivex update <dependency>
qutivex tree
qutivex list
```

Gradle may still be used temporarily by:

```text
qutivex run
qutivex test
qutivex build
```

---

## 3.5.24 — Qutivex Self Update

Add:

```text
qutivex update
```

with no dependency coordinate to update Qutivex itself.

Semantics:

```text
qutivex update
→ update Qutivex CLI

qutivex update group:artifact:version
→ update project dependency
```

Also support:

```text
qutivex update --check
```

Example:

```text
Current: 0.3.0
Latest:  0.3.1
Update available.
```

---

## 3.5.25 — Windows MSI Self Update

For MSI installs, never overwrite `Program Files` manually.

Flow:

```text
qutivex update
    ↓
query latest release
    ↓
compare versions
    ↓
download MSI
    ↓
obtain expected SHA-256
    ↓
verify MSI
    ↓
launch Windows Installer
    ↓
upgrade existing Qutivex installation
```

MSI packaging must preserve proper upgrade identity so releases upgrade instead of duplicating installations.

---

## 3.5.26 — Self-Update Security

Required:

```text
release metadata
    ↓
expected SHA-256
    ↓
download MSI
    ↓
actual SHA-256
    ↓
compare
    ↓
install only on exact match
```

Never execute an unverified installer.

---

## 3.5.27 — Shared HTTP Infrastructure

Repository downloads and self-update may share low-level HTTP infrastructure:

- streaming
- timeout
- retry
- redirect handling
- status validation
- user-agent
- cancellation

Keep repository logic separate from release-update logic.

---

## 3.5.28 — Performance

Phase 3 baseline:

```text
add                         ~1.7s
warm install                ~3.3s
offline + frozen            ~1.9s
tree                        ~0.5s
```

Benchmark Phase 3.5:

- native add
- native cold install
- native warm install
- native update
- native remove
- POM parsing
- graph resolution
- cache lookup
- offline install
- offline+frozen

Only claim improvements that measurements prove.

---

## 3.5.29 — Tests

Unit test:

- coordinate parsing
- repository URL generation
- POM parsing
- parent inheritance
- property interpolation
- dependencyManagement
- BOM imports
- exclusions
- optional dependencies
- scopes
- conflict resolution
- cycle detection
- deterministic graph
- deterministic lockfile
- SHA-256
- cache corruption
- atomic downloads
- concurrent download deduplication

Integration fixtures:

- Kotlin stdlib
- kotlinx-coroutines
- JUnit Jupiter
- Ktor JVM
- BOM-based dependency
- parent-POM dependency
- exclusion case
- optional dependency
- conflicting versions
- POM-only dependency

---

## 3.5.30 — Gradle Independence Test

Release-blocking test:

Make Gradle dependency resolution unavailable.

Then verify:

```text
qutivex add
qutivex remove
qutivex install
qutivex update <dependency>
qutivex tree
qutivex list
```

still work.

Do not mark Phase 3.5 complete if any package-lifecycle command secretly launches Gradle.

---

## 3.5.31 — Acceptance Workflow

```text
qutivex init native-test
cd native-test

qutivex add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
qutivex add --test org.junit.jupiter:junit-jupiter:5.12.2

qutivex tree
qutivex install
qutivex install --frozen
qutivex install --offline
qutivex install --offline --frozen

qutivex update <dependency>
qutivex remove <dependency>
```

During dependency commands:

```text
Gradle processes started by Qutivex = 0
```

Then verify:

```text
qutivex run
qutivex test
qutivex build
```

still work with the temporary Gradle build backend.

---

## Phase 3.5 Definition of Done

- [x] native Maven repository client
- [x] native POM parser
- [x] parent POM inheritance
- [x] property interpolation
- [x] dependencyManagement
- [x] imported BOMs
- [x] exclusions
- [x] optional dependencies
- [x] correct runtime/test scopes
- [x] deterministic conflict resolution
- [x] cycle detection
- [x] Qutivex-owned artifact cache
- [x] concurrent download deduplication
- [x] `qutivex add` is Gradle-free
- [x] `qutivex remove` is Gradle-free
- [x] `qutivex install` is Gradle-free
- [x] dependency `qutivex update` is Gradle-free
- [x] `qutivex tree` remains Gradle-free
- [x] `qutivex list` remains Gradle-free
- [x] offline mode has zero network
- [x] frozen mode is immutable
- [x] offline+frozen has zero network and zero Gradle
- [x] deterministic lockfile
- [x] SHA-256 integrity preserved
- [x] existing `run/test/build` still work
- [x] `qutivex update` self-updates Qutivex
- [x] MSI self-update verifies SHA-256
- [x] tests pass
- [x] benchmarks published
- [x] docs updated

## Status

✅ Complete

---

# Phase 4 — Native Kotlin/JVM Build Engine ✅ COMPLETE

## Milestone

Remove Gradle from normal Kotlin/JVM `build`, `run`, and `test`.

Target:

```text
Qutivex
   ↓
Qutivex Build Engine
   ↓
Kotlin Compiler
   ↓
JVM
```

## Planned Work

- source scanner
- compile/runtime/test classpath builder
- managed Kotlin compiler
- native `qutivex build`
- native `qutivex run`
- native `qutivex test`
- JUnit Platform invocation
- JAR packaging
- source/dependency/toolchain fingerprints
- incremental compilation strategy
- safe build cache
- optional lightweight Qutivex daemon only after benchmarking

## Definition of Done

- [x] build works without Gradle
- [x] run works without Gradle
- [x] test works without Gradle
- [x] compiler managed by Qutivex
- [x] classpaths generated by Qutivex
- [x] native JAR packaging
- [x] no-change builds measured
- [x] safe build cache
- [x] normal Kotlin/JVM project no longer requires Gradle

## Status

✅ Complete

---

# Phase 5 — Daily Development, IDE Bridge, and Stable 1.0 ⏳ PLANNED

## Planned

- IntelliJ bridge/model refresh
- dependency navigation
- application/library templates
- toolchain upgrades
- dependency explanations
- cache inspection
- improved diagnostics
- file watching after incremental builds are stable
- stable manifest contract
- stable lockfile contract
- migration rules
- stable exit codes
- concurrency/interruption hardening
- damaged-cache recovery
- permissions/long-path tests
- CI examples
- cross-platform release matrix
- troubleshooting docs
- upgrade documentation
- maintenance policy

## Stable 1.0 Goal

A clean machine and CI environment can:

```text
install
init
add
install dependencies
run
test
build
update dependencies
reproduce frozen state
upgrade Qutivex
```

reliably.

## Status

⏳ Planned

---

# Phase 6 — Long-Term Tracks ⏳ FUTURE

| Track | Direction |
|---|---|
| Android | Qutivex orchestration over Android SDK, AAPT2, D8, R8, APK/AAB/signing |
| Kotlin Multiplatform | target-aware source sets, metadata and toolchains |
| Workspaces | multi-project dependency/build model |
| Maven Publishing | library metadata, credentials, signing and provenance |
| Custom Repositories | repository precedence, authentication, policy |
| Plugins | versioned extension contracts |
| Global Tools | isolated executable/tool environments |
| Native Launcher | only if benchmarks justify it |
| Remote Build Cache | larger-team optimization |
| Advanced Versions | richer constraints/version policy after exact-version stability |

## Status

⏳ Future

---

# Architectural Generations

```text
Generation 1
Qutivex UX
   ↓
Gradle handles dependency + build
```

```text
Generation 2 — current completed foundation
Qutivex owns:
manifest
lockfile
security
package lifecycle
tree/update
distribution

Gradle still resolves and builds
```

```text
Generation 3 — Phase 3.5
Qutivex owns:
repository access
POM parsing
dependency resolution
artifact cache
dependency graph
lockfile

Gradle only builds
```

```text
Generation 4 — Phase 4
Qutivex owns:
dependency management
compiler orchestration
build
run
test
cache
packaging

Gradle removed from normal Kotlin/JVM workflow
```

---

# Release Milestone View

```text
v0.1.x
Foundation
✅ Complete

v0.2.x
Package lifecycle / lockfile / offline / frozen / security
✅ Complete

v0.3.0
tree / update / distribution / benchmarks
✅ Complete

v0.3.5
Native dependency engine
✅ Complete

v0.4.0
Native Kotlin build engine
✅ Complete

v1.0.0
Stable independent Kotlin/JVM workflow
⏭️ Phase 5

Post-1.0
Android / KMP / workspaces / plugins / publishing
⏳ Phase 6
```

> **Long-term identity:** Qutivex — a Kotlin-native package manager and build system.
