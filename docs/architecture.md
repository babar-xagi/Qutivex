# Architecture

## Current boundaries

Qutivex has three Gradle modules. Packages live under `dev.qutivex`; this is a local
namespace, not a claim of ownership of a publishing domain.

```text
:cli -----> :engine -----> :core
  |                         ^
  +-------------------------+
```

| Module | Responsibility | Current implementation |
| --- | --- | --- |
| `modules/core` | Values, validation, and later manifest/lock/graph rules | `project.ProjectSpec` |
| `modules/engine` | Filesystem, backend, repository, cache, and process operations | `project.ProjectInitializer` |
| `modules/cli` | Parse arguments, dispatch commands, format output and errors | `MainKt`, `QutivexCli` |

`core` must not import the CLI or engine or perform I/O. `engine` must not print to
the terminal or terminate the JVM. The CLI translates operation errors into exit
codes; only `main` exits the process. Constructors do not download or start services.

Keep features together within these modules. Add `manifest`, `lockfile`, and
`dependency` packages to core when their contracts exist. Add `backend/gradle`,
`cache`, `toolchain`, and `process` packages to engine when implemented. Introduce
interfaces at real testing/backend boundaries, and extract another Gradle module
only when independent dependencies or distribution justify it. Avoid empty modules
and a catch-all `util` package.

## Project initialization today

`QutivexCli` parses `init [directory]` and resolves the destination relative to its
working directory. `ProjectInitializer` validates its name before creating files,
requires a new or empty directory, and writes new UTF-8 files without overwriting.
If I/O fails halfway through, it reports failure and preserves partial output for
inspection; it does not delete a tree that may contain user files.

Generated project:

```text
hello/
  qutivex.toml
  src/main/kotlin/Main.kt
  src/test/kotlin/
  .gitignore
  README.md
```

No lockfile is written before real resolution. The template contains an explicit
`MainKt` entry point so the first runner does not need to guess. The current
implementation writes the manifest but does not yet parse arbitrary TOML.

## Planned execution flow

```text
CLI -> parse/validate manifest -> acquire project lock
    -> compare manifest + toolchain + backend fingerprint with qutivex.lock
    -> resolve if policy allows -> verify/fetch required artifacts
    -> materialize disposable backend files -> compile/test/run
```

Manifest parsing will use a maintained TOML parser. Report unknown schema versions,
duplicate keys, unsupported fields, and invalid values with file/line context.
Do not treat Maven versions as npm SemVer; the first manifest supports exact release
versions only. Preserve comments/formatting during dependency edits or document and
test the chosen canonical formatting before enabling those edits.

The first backend will generate files under `<project>/.qutivex/gradle/` with source
sets explicitly pointing to `<project>/src` and outputs to `<project>/build`.
Qutivex will pin the backend, configure Kotlin/JVM variants, and use structured
Gradle resolution results rather than scraping terminal logs. See
[the backend decision](decisions/0001-gradle-backend.md).

`qutivex.toml` is the editable source of truth; `qutivex.lock` is committed resolved
state. Backend scripts, Gradle locks, and verification configuration are derived
files. Recreating `.qutivex` from the manifest and lock must preserve the result.
Generated build script strings need proper escaping; user names, dependency values,
and paths must never be interpolated as executable Kotlin or shell fragments.

## Resolution and integrity contract (planned)

The versioned lock format must record normalized manifest input, backend/template
versions, compiler/toolchain identities, repository identity, selected variants,
configuration-specific graphs, artifact coordinates, and SHA-256 values. Include
compile, runtime, test, and build-tool dependencies; a list of application JAR versions
is insufficient. Stable ordering and portable paths keep diffs reproducible.

The adapter must preserve POM inheritance, properties, dependency management/BOMs,
exclusions, optional dependencies, Maven scopes, and Gradle JVM variant selection.
Unsupported behavior must fail explicitly. Gradle's resolution policy is the initial
policy; do not introduce a second conflicting version-selection algorithm.

Use HTTPS, repository allowlists, checksum validation, temporary downloads followed
by atomic replacement, and a lock around project mutations. Reuse Gradle's artifact
cache initially; do not maintain a duplicate downloader/cache in the first backend.
No dependency installation hooks or arbitrary manifest scripts in the MVP.
Hashes recorded on first resolution detect subsequent changes; they alone do not
prove a newly downloaded package is trustworthy. Pin trusted bootstrap distribution
checksums separately and review changes to the committed dependency verification data.

Changes to manifest and lock need a recoverable transaction: stage both, resolve and
verify first, then commit with a journal/recovery strategy under the project lock.
Renaming two files individually is not an atomic two-file transaction.

## Processes and toolchains (planned)

The JDK that launches Qutivex, the backend runtime, the build JDK, and emitted JVM
target are distinct settings. Initially require JDK 21 and align compilation to 21.
The manifest's `jvm = 21` is a major-version request; exact vendor, patch, platform,
and download checksums belong in toolchain resolution before reproducibility claims.

Process adapters will use argument lists, correct working directories, streamed I/O,
exit-code forwarding, and cancellation of child processes. Handle Windows launchers
explicitly and test spaces, Unicode paths, and shell metacharacters. Secrets must not
be written to generated scripts, manifests, or logs.

## IDE support (planned)

IntelliJ does not automatically understand `qutivex.toml`. The MVP must document how
to import its generated Gradle bridge, map source roots to the project, and refresh
it after manifest changes. A dedicated IDE plugin is a later improvement. The Qutivex
repository itself already imports as a normal Gradle project.

## Validation strategy

Current tests cover pure model rules and filesystem/CLI behavior in temporary
directories. Add local fixture repositories for resolution tests, avoiding Maven
Central in routine unit tests. Backend integration tests must cover transitive JVM
dependencies, BOMs, variants, test scope isolation, frozen/offline behavior, corrupted
artifacts, and recovery after interrupted writes. Release smoke tests must launch
the packaged CLI outside its source repository on all three operating systems.
