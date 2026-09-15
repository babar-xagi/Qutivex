🚀 Create Qutivex v0.4.1 — Toolchain Management & Automatic Project Environments

🎯 Goal:
Make Qutivex self-contained like Rustup + Cargo, with managed Kotlin/JDK toolchains and automatic isolated project environments.

🧰 Toolchain Management
Add:

qutivex toolchain list
qutivex toolchain install kotlin <version>
qutivex toolchain install jdk <version>
qutivex toolchain use kotlin <version>
qutivex toolchain use jdk <version>
qutivex toolchain remove <type> <version>
qutivex toolchain update

Store toolchains in:

~/.qutivex/toolchains/
├── kotlin/
└── jdk/

Read required versions from qutivex.toml:

[toolchain]
kotlin = "2.4.10"
jvm = 21

If a required toolchain is missing, Qutivex should automatically install it or provide a clear recovery message.

📦 Automatic Project Environment

Each project should automatically use its own isolated environment:

.qutivex/
├── env/
├── build/
├── classes/
├── state/
└── cache/

Track:
- Kotlin version
- JDK version
- dependency graph
- classpath
- compiler options
- build fingerprints
- project state

No activate/deactivate commands.

Example:

cd project-a
qutivex run
→ automatically uses project-a environment

cd project-b
qutivex run
→ automatically uses project-b environment

Add:

qutivex env info
qutivex env clean
qutivex env recreate

🔒 Reproducibility

The same:

qutivex.toml
qutivex.lock
Kotlin version
JDK version

should recreate the same project environment on another machine.

⚙️ Integration

Integrate this with:

qutivex build
qutivex run
qutivex test
qutivex install
qutivex doctor

Do not break the Phase 4 native build engine.

🦀 Philosophy

Rust:
rustup → toolchains
cargo → packages + build

Qutivex:
qutivex toolchain → Kotlin/JDK toolchains
qutivex → dependencies + build + run + test
automatic environments → project isolation

🧪 Tests

Add comprehensive tests for:
- install/list/use/remove toolchains
- automatic toolchain selection
- different Kotlin versions across projects
- different JDK versions
- environment isolation
- env clean/recreate
- missing toolchain recovery
- offline behavior
- reproducibility
- build/run/test with managed toolchains

📚 Finish

- bump version to 0.4.1
- update README
- update architecture docs
- update CLI docs
- update CHANGELOG
- add benchmarks
- provide final implementation report
