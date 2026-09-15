🛠️ Qutivex v0.4.1 — Phase 1 CLI Contract Fixes

Fix all discrepancies discovered during documentation-driven Phase 1 verification.

1. Remove stale Gradle wording

Current incorrect help:

qutivex run --help
→ Show detailed Gradle execution logs

qutivex test --help
→ Show complete Gradle test output and tasks

qutivex build --help
→ Show complete Gradle build output and tasks

Qutivex v0.4.1 uses the native Kotlin/JVM build engine.

Replace these with native wording such as:

run:
-v, --verbose  Show detailed native build and execution diagnostics

test:
-v, --verbose  Show detailed native compilation and test-runner diagnostics

build:
-v, --verbose  Show detailed native build and packaging diagnostics

Search all current documentation/help strings for outdated Gradle runtime/backend claims.

Do not rewrite historical CHANGELOG entries that were correct for older releases.

2. Resolve project-name contract inconsistency

Observed behavior:

qutivex init "Hello World"
→ succeeds, project name becomes hello-world

qutivex init "UPPERCASE"
→ succeeds, project name becomes uppercase

qutivex init "bad_name"
→ succeeds

Current docs claim only lowercase letters, numbers and hyphens are valid.

Choose and enforce one canonical contract.

Preferred behavior:
- directory names may contain spaces/case
- generated [project].name is normalized deterministically
- lowercase output
- spaces/underscores normalize to hyphens
- invalid repeated separators are normalized
- final project name must use [a-z0-9-]
- max length 64
- reject names that cannot produce a valid project name

Examples:

"Hello World" → hello-world
"UPPERCASE" → uppercase
"bad_name" → bad-name
"hello-123" → hello-123

Update CLI help, docs and automated tests to match the chosen contract exactly.

3. Fix toolchain list active/default visibility

Outside a project:

qutivex toolchain use kotlin 2.4.10
qutivex toolchain list kotlin

should display the selected global default, for example:

• 2.4.10 (default) [managed] (...)

Inside a project, the manifest-selected toolchain should display:

• 2.4.10 (active) [managed] (...)

Clearly distinguish:
- project active
- global default
- merely installed

Do the same for JDK.

4. Verify CLI exit-code contract

Add tests ensuring:
- successful commands/help → 0
- operational failures → 1
- invalid syntax/options/arguments → 2

Cover:
qutivex help
init
add
remove
tree
install
toolchain
env

5. Add init edge-case tests

Test:
- qutivex init with no directory
- qutivex init -- <directory>
- empty existing directory
- non-empty directory protection
- 64-character normalized project name
- >64-character project name
- spaces
- uppercase
- underscores
- invalid-only names
- path name vs manifest project-name normalization

6. Documentation audit

Update current:
- CLI help
- docs/cli.md
- docs/architecture.md where applicable
- README
- docs/toolchains.md

Do not modify historical CHANGELOG statements unless they are factually wrong for that historical version.

7. Verification

Run full automated suite:

.\gradlew.bat test check

Then perform real installed CLI E2E tests on Windows.

Produce a Phase 1 verification report containing:

✅ commands tested
✅ expected vs actual exit codes
✅ project-name normalization table
✅ help text verification
✅ toolchain active/default proof
✅ docs corrected
✅ automated test results
✅ final Phase 1 PASS/PARTIAL/FAIL verdict
