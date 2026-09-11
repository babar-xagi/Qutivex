# Contributing

Use JDK 21 and open the repository as a Gradle project. Run
`.\gradlew.bat check :cli:installDist` on Windows or
`sh ./gradlew check :cli:installDist` on Linux/macOS before proposing a change.

Follow `.editorconfig` and Kotlin's official code style. Shared dependency/plugin
versions belong in `gradle/libs.versions.toml`; the application version belongs in
the root build script. Runtime dependencies should have a concrete use case.

Keep command parsing/output in `cli`, pure models and rules in `core`, and I/O in
`engine`. Add tests for behavior and failure cases alongside the affected module.
Avoid network-dependent unit tests or writes outside temporary test directories.
Do not add empty architecture layers or success messages for unfinished commands.

Update the command specification and roadmap status when a feature becomes usable.
Record changes to the manifest/lock schema or backend policy in `docs/decisions`.
These formats are experimental until 1.0; changes must still be versioned and older
unsupported formats rejected clearly.

For a pull request, explain the user-facing behavior, relevant design decisions,
and commands used to validate it. Keep generated build output and caches out of Git.
