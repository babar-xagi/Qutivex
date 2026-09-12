Implement Qutivex Phase 3.5: Native Dependency Engine.

Goal:
Remove Gradle completely from dependency management while keeping Gradle temporarily only for build/run/test until Phase 4.

Implement:

1. Native Maven Central repository client in Kotlin.
2. Native POM parser with:
  - parent POMs
  - properties
  - dependencyManagement
  - imported BOMs
  - exclusions
  - optional dependencies
  - Maven scopes
3. Native deterministic dependency graph resolver.
4. Deterministic conflict/version selection with clear reasoning.
5. Qutivex-owned artifact cache under ~/.qutivex/cache/.
6. SHA-256 verification, atomic downloads, corruption detection, and concurrent-download deduplication.
7. Rewrite these commands so they NEVER invoke Gradle:
  - qutivex add
  - qutivex add --test
  - qutivex remove
  - qutivex remove --test
  - qutivex install
  - qutivex install --offline
  - qutivex install --frozen
  - qutivex install --offline --frozen
  - qutivex update <dependency>
  - qutivex tree
  - qutivex list
8. Keep qutivex.lock deterministic and compatible where possible.
9. Preserve rollback safety and frozen/offline guarantees.
10. Ensure offline+frozen performs:
  - zero network
  - zero Gradle
  - zero manifest mutation
  - zero lockfile mutation
11. Add:
    qutivex update
    with no dependency argument to self-update Qutivex.
12. On Windows MSI installs:
  - check latest release
  - download new MSI
  - verify SHA-256
  - launch MSI upgrade
  - never manually overwrite Program Files.
13. Keep:
    qutivex update group:artifact:version
    for dependency updates.
14. Add comprehensive unit/integration tests for POM parsing, BOMs, exclusions, conflicts, cycles, scopes, cache integrity, offline mode, and Gradle independence.
15. Benchmark native add/install/update versus Phase 3 baseline.
16. Update README.md, architecture.md, security.md, cli.md, CHANGELOG.md, and roadmap.

Definition of done:
All dependency lifecycle commands work even if Gradle dependency resolution is unavailable.

At the end, Gradle may only remain for:

qutivex build
qutivex run
qutivex test

Provide a final implementation report with:
- architecture changes
- files added/changed
- resolver design
- cache design
- POM/BOM behavior
- conflict policy
- test results
- benchmark results
- proof that dependency commands no longer launch Gradle
- release version/tag.
