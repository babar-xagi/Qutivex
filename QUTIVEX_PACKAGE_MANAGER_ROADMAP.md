# Qutivex Package Manager Roadmap

Qutivex aims to give Kotlin developers the simple project workflow associated
with tools such as Bun and uv: one command to create a project, add a dependency,
run it, test it, and build it. Qutivex is implemented in Kotlin.

The first supported ecosystem is Kotlin/JVM with Maven Central dependencies.
The initial execution backend is pinned Gradle. Qutivex owns the user experience,
manifest, lifecycle rules, and diagnostics; Gradle handles compatible resolution,
compilation, testing, and packaging.

## Current status

This repository contains the foundation, not a usable package manager yet.

| Capability | Status |
| --- | --- |
| Kotlin modules `:core`, `:engine`, and `:cli` | Implemented foundation |
| `qutivex --help` and `qutivex --version` | Implemented |
| `qutivex init [directory]` | Implemented project scaffold |
| Reading manifest (`schema-version = 1`) | Implemented strict validation |
| Running, testing, and building generated projects (`run`, `test`, `build`) | Implemented Gradle-backed preview |
| Managed Gradle backend and JDK bootstrap | Implemented pinned Gradle backend |
| Dependency resolution, installation, cache, and lockfile | Implemented (Phase 2) |
| Public cross-platform alpha distribution, tree, update, and benchmarks | Implemented (Phase 3) |
| IDE bridge and toolchain upgrades | Planned (Phase 4) |

An initialized project contains a valid manifest and Kotlin source.
Projects can now be run through `qutivex run`, tested through `qutivex test`,
and built through `qutivex build`. Help lists implemented commands
and makes future functionality clear.

See [README](README.md) for commands that work today,
[CLI specification](docs/cli.md) for command behavior,
[architecture](docs/architecture.md) for module boundaries, and
[the backend decision](docs/decisions/0001-gradle-backend.md) for the first backend.

## Product boundaries

The first usable MVP supports a single Kotlin/JVM application, one main entry
point, Maven Central dependencies, and a standard source layout. It includes
`init`, `add`, `install`, `remove`, `run`, `test`, and `build`.

A new user must be able to install Qutivex and finish that workflow using documented
Java requirements and a reliable bootstrap path. Downloading JARs alone does not
meet the MVP definition.

Defer Android, Multiplatform targets, custom registries, arbitrary build plugins,
workspaces, publishing, and a custom dependency resolver. The MVP documents a basic
IntelliJ import route; Phase 4 improves model refresh and daily workflow polish.
An official IDE plugin can come later.

Use exact versions and explicit coordinates first. Support one add syntax:

```text
qutivex add group:artifact@exact-version
qutivex add --test group:artifact@exact-version
qutivex remove group:artifact
```

The syntax above is a specification, not an implemented command today.
Do not introduce aliases, snapshots, dynamic versions, caret constraints, or
implicit latest-version selection until resolution and update policies exist.

Use `install` as the single command for bringing local project dependencies into
agreement with the manifest and lockfile. Do not add a duplicate `sync` command.

## Manifest and project layout

The initializer currently emits this proposed schema:

```toml
schema-version = 1

[project]
name = "hello"
version = "0.1.0"

[toolchain]
kotlin = "2.4.10"
jvm = 21

[application]
main-class = "MainKt"

[dependencies]

[test-dependencies]
```

Dependency tables will map quoted Maven coordinates to exact version strings.
For example, a key has the form `"group:artifact"`; its value must be a concrete
version accepted by the supported backend. A placeholder is not an installable
version. Test dependencies map to the test classpaths, not production runtime.

The Kotlin version is an explicit compiler/plugin pin. Currently `jvm = 21`
expresses the intended build JDK major and language target. It does not pin a JDK
vendor, patch release, operating system, or archive digest. Exact toolchain
identity belongs in the future lock/bootstrap design; the current scaffold does
not provide fully reproducible builds.

The Kotlin and Java targets must be aligned, and supported Kotlin/Gradle/JDK
combinations must be tested. The JDK that launches the backend and the toolchain
used to compile the application are separate concepts.
[Kotlin Gradle configuration](https://kotlinlang.org/docs/gradle-configure-project.html)

```text
hello/
  qutivex.toml                  # User configuration; commit
  qutivex.lock                  # Planned resolved state; commit when implemented
  src/main/kotlin/Main.kt
  src/test/kotlin/
  README.md
  .gitignore
  .qutivex/gradle/              # Planned generated backend; ignore
  build/                       # Planned generated outputs; ignore
```

The manifest is the single user-maintained source of configuration. Generated
Gradle settings and scripts are disposable implementation output. They must refer
to the project's real source paths and place results under its `build/` directory.

## Repository architecture

Keep three modules until implementation demonstrates a useful additional boundary.

```text
qutivex/
  modules/
    core/                      # :core - models and validation
    engine/                    # :engine - project operations and adapters
    cli/                       # :cli - arguments, command routing, output
  docs/
    architecture.md
    cli.md
    decisions/0001-gradle-backend.md
  gradle/
  build.gradle.kts
  settings.gradle.kts
  README.md
  QUTIVEX_PACKAGE_MANAGER_ROADMAP.md
```

Dependencies flow from `:cli` to `:engine` to `:core`; the CLI may also use core
models directly. Keep filesystem, network, process, and terminal behavior outside
core. Add packages within engine for manifest IO, backend generation, process
execution, locking, and toolchains as those features arrive.

The repository Gradle build produces Qutivex itself. The future project backend
builds projects created by Qutivex. These have different responsibilities and
must not accidentally share generated settings or mutable project state.

Reuse Gradle's initial cache and resolution machinery behind an adapter.
Kotlin libraries can expose platform variants and metadata beyond Maven POMs;
Gradle Module Metadata supports variant-aware resolution.
[Gradle Module Metadata](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html)

## Phase 0 - Product contract and CLI foundation

**Milestone:** the implemented scaffold is a clear, maintainable starting point.

- Keep `--help`, `--version`, and `init [directory]` usable from the distribution.
- Generate the proposed manifest, console source, test directory, and README.
- Validate project names and report input errors with useful exit codes.
- Protect existing files and reject unsafe or conflicting initialization targets.
- Keep tests beside each module and document how contributors build the CLI.

**Acceptance:** initialize a new directory offline, inspect the generated files,
and verify that a repeated initialization cannot overwrite them. Check paths with
spaces and invalid arguments. No initialization success message may imply that
dependency installation or execution already exists.

## Phase 1 - Runnable Gradle-backed preview (Implemented)

**Milestone:** a developer can run, test, and build one generated Kotlin/JVM application.

- Parse and validate schema version 1, including unknown or unsupported fields.
- Generate a pinned Gradle backend under `.qutivex/gradle/`.
- Establish backend, plugin, metadata, and artifact verification with the first downloads.
- Implement `run`, `test`, and `build` through the adapter.
- Use the configured main class and forward arguments after `run --`.
- Preserve application input/output, exit status, and cancellation behavior.
- Add an initial real dependency through a manually edited manifest.
- Provide clear errors for missing Java, repository failures, and compilation errors.

The first preview may require an installed supported JDK. Document that requirement.
Backend bootstrap, plugin resolution, and application dependency resolution are
separate download paths, each requiring an integrity policy.

**Acceptance:** initialize a project, compile and run it, execute both a passing
and a failing test, and create documented application output. A real transitive
dependency must resolve to the correct JVM artifacts. Verify argument forwarding
and working directories, including Windows paths with spaces.

This is a runnable preview. It is not the reproducible package lifecycle MVP.

## Phase 2 - Reproducible package lifecycle MVP

**Milestone:** complete the essential dependency lifecycle and a supported setup path.

- Implement exact-version `add`, `remove`, and `install`.
- Commit manifest and lock changes only after the requested operation succeeds.
- Define the versioned `qutivex.lock` format before claiming lockfile support.
- Resolve and lock compile, runtime, test, compiler, and relevant build tooling state.
- Validate artifact and metadata integrity and preserve repository identity.
- Implement `install --frozen` and `install --offline`, including their combination.
- Let `run`, `test`, and `build` share the same installation and lock rules.
- Provide a tested installable distribution and documented JDK discovery/bootstrap.
- Document importing the generated Gradle bridge and refreshing it after manifest changes.
- Reuse the backend cache and coordinate concurrent project mutations.

The lock format is still a design task. It should record schema/backend identity,
a normalized manifest fingerprint, resolved configuration and variant information,
artifact identity and hashes, repository identity, and toolchain requirements.
A practical first format may wrap backend lock and verification state rather than
implementing a second resolution engine. Store only hashes produced by the
verified download workflow; the lock schema is not implemented yet.

Gradle version locking and dependency verification are separate mechanisms.
`lockAllConfigurations()` excludes buildscript configurations, which need separate
handling. Resolve every supported configuration before persisting complete state.
[Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html),
[Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)

| Operation | Required behavior |
| --- | --- |
| `install` | Reuse a matching lock; create or reconcile state when needed |
| `install --frozen` | Require matching lock state; never change manifest or lock |
| `install --offline` | Make no network request; fail clearly on missing cached inputs |
| `add` / `remove` | Resolve the proposed change before committing project state |
| Failed operation | Preserve valid project state and remove incomplete temporary files |

Hashes detect changed bytes; merely recording a first download does not establish
its authenticity. Specify how checksums or signatures are trusted at bootstrap,
and how initial dependency verification data is reviewed and updated. Frozen
operation must never silently accept replacement bytes.

**Acceptance:** finish `init -> add -> run -> test -> build -> remove` through the
installed command. Reproduce dependencies on a second machine from committed state.
Reject a stale/missing lock in frozen mode, reject a tampered artifact, and finish
a warm offline install. A missing offline artifact must identify the missing input.
Delete disposable backend state and regenerate it without changing the lock.

## Phase 3 - Public alpha distribution and measured performance (Implemented)

**Milestone:** fresh users can complete the MVP reliably across supported platforms.

- Test install, upgrade, uninstall, and launch on Windows, Linux, and macOS.
- Explain the CLI runtime JDK requirement and application JDK selection.
- Harden backend/JDK bootstrap with pins, digest checks, and interrupted-download recovery.
- Add `list`, `tree`, and explicit update behavior in this checkpoint if not delivered in Phase 2.
- For exact pins, define `update group:artifact@version` before adding automatic upgrades.
- Report selected versions and reasons for important transitive changes.
- Benchmark CLI startup, first bootstrap, cold/warm install, and no-change run.
- Measure incremental rebuild time, memory use, and cache size on documented fixtures.

The initial JVM distribution may contain launch scripts and JARs. Evaluate bundled
runtimes or a native launcher after measuring startup and installation cost.
A native launcher does not remove the JDK requirement for building JVM projects.

**Acceptance:** fresh-machine smoke tests pass with a documented installation route.
Publish reproducible measurements separating startup, network, resolution, and
compilation. Compare equivalent Kotlin workflows; make no unsupported Bun/uv speed
claims. Help/version/init should avoid launching the Gradle backend.

## Phase 4 - Daily development and IDE bridge beta

**Milestone:** developers can use Qutivex for ordinary ongoing Kotlin/JVM work.

- Provide `doctor` for local toolchain, configuration, and cache diagnostics.
- Improve the IntelliJ bridge with reliable model refresh after dependency/toolchain changes.
- Specify how the generated project model stays synchronized with the manifest.
- Add console and library templates when their build outputs are supported.
- Support explicit toolchain upgrades with compatibility checks and lock changes.
- Add dependency explanations, actionable diagnostics, and focused cache inspection.
- Consider file watching only after cancellation and incremental builds are reliable.
- Design version policies, BOMs, exclusions, and repository configuration before exposing them.
- Once stable-release and Maven version-ordering policies are tested, support
  `add group:artifact`: show the selected stable release and save an exact pin.

**Acceptance:** open an initialized project in IntelliJ, navigate a resolved library,
change a dependency, refresh the model, run tests, and reproduce the CLI build.
Upgrade the toolchain deliberately and explain incompatible changes without
silently rewriting project settings.

## Phase 5 - Stable 1.0 hardening

**Milestone:** stable documented contracts for the supported JVM scope.

- Stabilize manifest, lock, CLI, output locations, exit codes, and migration rules.
- Exercise interrupted writes, concurrent installs, bad metadata, and damaged caches.
- Validate reproducibility and integrity coverage across supported configurations.
- Test process handling, long paths, permissions, and platform-specific artifacts.
- Publish CI examples with frozen installation, testing, and building.
- Complete user documentation, troubleshooting, release checksums, and upgrade notes.
- Define supported Kotlin/Gradle/JDK combinations and a maintenance policy.

**Acceptance:** a fresh supported machine and CI can install, run, test, build, and
upgrade documented example projects. Release checks pass across the supported OS
matrix. No required lock or bootstrap behavior remains described as experimental.

## Phase 6 - Independent long-term tracks

Start these only when the stable JVM workflow justifies their maintenance cost.

| Track | Entry condition |
| --- | --- |
| Global tools and formatter/linter management | Isolated tool environments and reliable launch semantics |
| Maven publishing | Library outputs, metadata, credentials, signing, and provenance design |
| Custom repositories and package discovery | Repository precedence and version-selection policy |
| Workspaces and larger builds | Stable project model and explicit dependency boundaries |
| Multiplatform and Android | Target-aware toolchains, metadata, locking, and supported IDE models |
| Plugins | Versioned extension contracts and clear execution permissions |
| Native CLI distribution | Measured benefit and verified platform/dependency compatibility |
| Custom build or resolver backend | Compatibility fixtures and evidence the current backend limits users |

A complete Kotlin toolchain is a possible outcome, not a prerequisite for success.
Keep the immediate priority on a small, trustworthy workflow that developers can
install and use with simple commands.

