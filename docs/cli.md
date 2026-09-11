# CLI specification

## Available commands

| Command | Behavior |
| --- | --- |
| `qutivex`, `qutivex --help`, `qutivex -h`, `qutivex help` | Show help without changing files |
| `qutivex --version`, `qutivex -V` | Show the version embedded by the build |
| `qutivex init [directory]` | Create a console project in a new or empty directory; default is current directory |
| `qutivex init --help` | Show initialization help |
| `qutivex run [-- arguments...]` | Compile and run configured main, forwarding arguments after `--` |
| `qutivex run --help` | Show run help |
| `qutivex test` | Compile and run project tests |
| `qutivex test --help` | Show test help |
| `qutivex build` | Compile and produce application distributions under `build/` |
| `qutivex build --help` | Show build help |
| `qutivex doctor` | Inspect local environment, Java installation, and build dependencies |
| `qutivex doctor --help` | Show doctor help |

Quote directory paths containing spaces. A `--` after `init` ends option parsing;
it does not bypass project-name validation. Names are normalized to lowercase, with
whitespace replaced by hyphens. Metadata names must start with an ASCII letter,
contain only letters, digits, hyphens or underscores, be at most 64 characters, and
avoid Windows device names. Nonempty directories and existing files are rejected.

The CLI does not prompt, contact repositories, or write a lockfile during init.
If initialization fails during writing, partial files are left for inspection.
Standard output contains successful command results; standard error contains errors.
Exit codes: `0` success, `2` invalid command/arguments, `1` an operation failed.
Unknown commands and unimplemented commands return nonzero.

## MVP contract (planned, unavailable today)

Use one command for each operation. `install` is the canonical synchronization
command; there is no separate `sync` or `new` alias in the initial interface.

| Command | Required behavior |
| --- | --- |
| `add group:artifact@version [--test]` | Add an exact release requirement, resolve and install; commit manifest and lock only after success |
| `remove group:artifact [--test]` | Remove from selected dependency group and synchronize remaining dependencies transactionally |
| `install` | Preserve a matching lock; resolve when missing/stale; materialize runtime and test dependencies |
| `install --frozen` | Require a matching lock; reject missing/stale state; never rewrite manifest/lock |
| `install --offline` | Use only local artifacts and metadata; fail with missing cache details; make no network requests |
| `list` | Display declared dependencies by scope |
| `tree` | Display locked transitive dependencies, configurations, and selection reasons |
| `update [group:artifact@version]` | Explicitly change exact direct requirements; with no argument refresh permitted transitive selections, retain exact direct pins |

`--frozen` and `--offline` will also apply to run/test/build and may be combined.
Frozen may download missing artifacts using committed integrity data; offline must
not download. New upstream releases alone do not invalidate a matching lock.
The Qutivex meaning of `--frozen` is its own strict contract; it is not an assertion
that every similarly named flag in Bun or uv behaves identically.

Initially reject unversioned additions, aliases, snapshots, ranges, and caret syntax
with actionable errors. Phase 4 adds `add group:artifact` with tested stable-release
selection: display the chosen version and save an exact pin. Ambiguous short aliases
and broader package discovery come later.

Global color/quiet/verbose/JSON settings and stable machine-readable errors are later
features. No placeholder flag should report success before its semantics exist.
