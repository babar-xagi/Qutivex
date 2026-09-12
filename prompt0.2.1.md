Qutivex Phase 2 Final Hardening

Goal:
Finish and prove the remaining Phase 2 reliability, reproducibility, offline, concurrency, and integrity requirements without breaking existing commands or changing the current user-facing project model.

Current Qutivex version is around 0.2.x and already supports:
- init
- add / add --test
- remove
- list
- install
- install --frozen
- install --offline
- run
- test
- build
- doctor
- qutivex.toml
- qutivex.lock
- transitive dependency locking
- SHA-256 artifact data
- Gradle-backed dependency/build backend
- atomic manifest rollback

Do NOT implement Phase 3 features such as update, tree, Android, Multiplatform, or a native resolver in this task.

==================================================
1. --offline + --frozen COMBINATION
   ==================================================

Implement and fully verify:

qutivex install --offline --frozen

Required behavior:

- Never access the network.
- qutivex.lock must already exist.
- qutivex.toml manifest hash must exactly match the lockfile.
- All required locked artifacts and metadata must exist locally.
- Do not modify qutivex.toml.
- Do not modify qutivex.lock.
- Do not silently fall back to online mode.

Success example:

qutivex install --offline --frozen
✅ Dependencies verified from local cache in 800ms

Manifest mismatch example:

error: Lockfile is out of sync with qutivex.toml in frozen mode.

Missing cached artifact example:

error: Offline installation cannot continue.
Missing artifact:
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2

Add unit and integration tests.

==================================================
2. TAMPERED ARTIFACT REJECTION
   ==================================================

The lockfile already stores SHA-256 values where applicable.

Use these hashes to verify cached/downloaded artifacts.

Required behavior:

- Before using a cached artifact, calculate its SHA-256.
- Compare it with the trusted hash recorded in qutivex.lock or verified integrity state.
- If bytes were modified, reject the artifact.
- Never automatically accept the changed file.
- Never rewrite the lockfile hash simply because local bytes changed.
- Online mode may re-download the artifact only according to an explicit safe recovery policy.
- Frozen mode must fail immediately.

Expected example:

error: Artifact integrity verification failed.

Artifact:
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2

Expected SHA-256:
5ca175...

Actual SHA-256:
9f08ab...

The cached artifact may be corrupted or modified.

Add an integration test that:
1. installs a real dependency
2. locates a cached artifact
3. modifies its bytes
4. runs frozen/offline installation
5. proves Qutivex rejects it

==================================================
3. MISSING OFFLINE ARTIFACT DIAGNOSTICS
   ==================================================

Offline failures must identify exactly what is missing.

Bad:

error: Dependency resolution failed.

Good:

error: Offline installation cannot continue.

Missing artifact:
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2

Expected cache location:
<path>

Run without --offline when network access is available.

Support missing:

- POM/metadata
- JAR
- required transitive artifact
- compiler/toolchain input if applicable

Do not expose unnecessary Gradle internals in normal mode.

--verbose may show backend diagnostics.

==================================================
4. CONCURRENT PROJECT MUTATION LOCKING
   ==================================================

Protect project-changing commands from simultaneous mutation.

Commands requiring project mutation protection include at least:

- qutivex add
- qutivex remove
- qutivex install when it may update lock state

Implement a project lock mechanism.

Example conceptual path:

.qutivex/project.lock

Requirements:

- Only one mutating Qutivex operation may modify a project at a time.
- Reads such as list should remain safe where practical.
- No manifest or lockfile corruption.
- Atomic writes must remain atomic.
- A crashed/stale process must not permanently block the project.
- Provide a useful timeout/error instead of hanging forever.

Example:

error: Another Qutivex operation is currently modifying this project.

Process: 12345
Operation: install

Wait for it to finish and retry.

Add concurrency tests that start two modifying operations at nearly the same time and verify:
- one safely waits or fails clearly
- qutivex.toml remains valid
- qutivex.lock remains valid
- no partial files remain

==================================================
5. COMPILER / BUILD-TOOL STATE LOCKING
   ==================================================

Improve qutivex.lock so reproducibility includes relevant build/toolchain identity, not only application dependencies.

Record enough information to identify the build environment used for the locked project.

At minimum consider:

[toolchain]
kotlin = "2.4.10"
jvm = 21

[backend]
type = "gradle"
gradle = "9.5.0"

Where applicable also record:

- Kotlin compiler/plugin version
- backend identity/version
- Gradle wrapper version
- verification digest for downloaded backend binaries
- relevant compiler/build tooling artifacts

Do not over-design the format, but ensure that:

- --frozen detects incompatible build-tool state
- silently changing backend/compiler versions is not allowed
- lockfile output remains deterministic

If exact JDK vendor/patch locking is intentionally not supported yet, document that clearly instead of pretending full JDK reproducibility exists.

==================================================
6. INTEGRITY TRUST / BOOTSTRAP POLICY
   ==================================================

Define a clear security model for initial downloads.

Important distinction:

A SHA-256 hash generated after downloading a file only proves future files are unchanged. It does not automatically prove the first download was trustworthy.

Document and implement the strongest practical policy available for Phase 2.

Cover separately:

A. Qutivex installer
- MSI SHA-256 release checksum

B. Gradle wrapper/distribution
- pinned version
- expected distribution SHA-256
- reject mismatching download

C. Maven artifacts
- repository identity
- artifact SHA-256
- immutable frozen behavior
- policy for first trusted download

D. Kotlin compiler/tooling
- pinned version
- integrity verification where available

E. Lockfile
- must never silently update integrity hashes during --frozen

Create documentation explaining:

Trusted source
↓
verified first download
↓
stored artifact hash
↓
future cache verification

Do not claim cryptographic authenticity if Qutivex only has integrity/change detection.

Use precise terminology:
- integrity verification
- trusted bootstrap
- repository identity
- checksum verification

==================================================
7. REGRESSION REQUIREMENTS
   ==================================================

The following existing workflows must continue to work:

qutivex init app
qutivex add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
qutivex add --test org.junit.jupiter:junit-jupiter:5.12.2
qutivex list
qutivex install
qutivex install --frozen
qutivex install --offline
qutivex install --offline --frozen
qutivex run
qutivex test
qutivex build
qutivex remove ...
qutivex doctor

Existing:
- rollback behavior
- quiet output
- --verbose output
- deterministic lockfile
- transitive dependency locking
  must not regress.

==================================================
8. REQUIRED TESTS
   ==================================================

Run:

.\gradlew.bat check

Add automated coverage for:

1. offline + frozen success
2. offline + frozen manifest mismatch
3. offline + frozen missing artifact
4. tampered cached JAR
5. tampered metadata where applicable
6. concurrent add/add
7. concurrent add/remove
8. interrupted project mutation
9. stale project lock recovery
10. backend/toolchain mismatch in frozen mode
11. deterministic lockfile output
12. no lockfile mutation during frozen install

==================================================
9. WINDOWS ACCEPTANCE TESTS
   ==================================================

Create a fresh project and run:

qutivex init phase2-final
cd phase2-final

qutivex add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
qutivex install
qutivex install --frozen
qutivex install --offline
qutivex install --offline --frozen

Then deliberately:

- modify qutivex.toml
- remove one cached artifact
- corrupt one cached artifact
- create a backend/toolchain mismatch
- start two mutating commands concurrently

For every case, capture the exact CLI output and prove that the project remains valid.

==================================================
10. DEFINITION OF DONE
    ==================================================

Phase 2 final hardening is complete only when:

✅ --offline --frozen performs zero network access
✅ stale manifest is rejected
✅ missing offline artifacts are identified precisely
✅ tampered artifacts are rejected
✅ concurrent mutations cannot corrupt project state
✅ backend/compiler versions are part of reproducible state
✅ frozen mode never silently rewrites integrity state
✅ bootstrap/integrity policy is documented accurately
✅ all automated tests pass
✅ Windows acceptance tests pass
✅ existing Qutivex functionality does not regress

Final report must include:

1. Files changed
2. Locking design
3. Artifact integrity design
4. Offline/frozen behavior
5. Build-tool locking additions
6. Bootstrap trust model
7. Test results
8. Exact commands used
9. Failure examples
10. Any remaining Phase 2 limitation

Do not mark Phase 2 complete based only on implementation.
Prove every requirement with automated tests and real CLI acceptance tests.
