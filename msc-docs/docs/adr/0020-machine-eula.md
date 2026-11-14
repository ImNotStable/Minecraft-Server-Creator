# ADR 0020: One Minecraft EULA acceptance per machine

Status: accepted, 2026-11-12; amended 2026-11-12 and 2026-11-14 (see the amendments at the end). Decided by Claude under Jeremiah's hand-over of build decisions. Roadmap item N1.

## Context

Paper and Fabric run Mojang's server, which starts only with `eula=true` in `eula.txt`. Until now every new dev server needed the owner's own "yes", relayed through the AI (`server_setup acceptEula=true`). In the Lumen Siege playtest that wait was 15 of 26 minutes, and every Paper or Fabric session paid it again. The owner may not be at the keyboard when a thread reaches `server_setup`, and the agreement must still be theirs: no tool, flag or default may accept it for them, and an acceptance must never land in a tracked file (as `msc-bench/bench.local.json` stays untracked).

## Decision

1. **The owner records the agreement once per machine and user, in `~/.msc/eula.json`.** It holds the EULA URL, `accepted: true`, the OS user name, the host name, the time and how it was given. It lives in the home folder, outside any repo, workspace and the install folder (installs live in `~/.msc/core/msc-core-*` and old ones are pruned; `eula.json` is one level up), so it can't be committed or wiped by a reinstall.
2. **Only one command writes it: `Main eula accept`**, run by the owner as `"<java>" -cp "~/.msc/core/msc-core-<version>-<time>/lib/*" io.github.imnotstable.msc.core.Main eula accept` (the newest install, ADR 0021). `installMsc` prints that line and saves it in `~/.msc/core/eula-accept.txt`, a fixed path that each install rewrites to point at its own jars; it never runs it. For a release-zip install, the core's own EULA messages give the same line for the install they run from. The command shows the EULA link and what agreeing means, then reads the answer from the console and writes the file only on `yes`. `eula status` shows it, `eula revoke` deletes it. *Amended 2026-11-14 (#383):* this is no longer the only writer; there are three, all an explicit opt-in by the owner. See the 2026-11-14 amendment.
3. **It refuses without an interactive terminal** (`System.console()` present and `isTerminal()`). Claude Code's Bash tool, Gradle `run`, CI and any pipe have no terminal, so an AI or script that runs the command gets "Refused" and nothing is written. `MachineEula.write` is package-private and called only from `EulaCommand`. *Amended 2026-11-14 (#383):* the terminal check still guards this command, but `MachineEula.write` is now public and also called from `core.tools.EulaPrompts`; see the 2026-11-14 amendment.
4. **MSC tools only read it.** No MCP tool and no tool argument writes `eula.json`. *Amended 2026-11-12 and 2026-11-14 (#383):* `acceptEula=true` on `server_setup` or `server_start` writes it the first time; see both amendments. When it is present:
   - `server_setup` writes `eula=true` with a comment naming who accepted and when into the new server's `eula.txt`, and says so instead of asking.
   - `server_start` (and so `dev_cycle`) does the same for a server set up before the acceptance, instead of refusing.
   - `project_scaffold` says the next step is `server_setup` with no EULA question.
   When it is absent, everything works as before: the AI asks the exact question, and the refusal and setup messages add the one-time command for the owner to run.
5. **The core finds the file from `user.home`, not an environment variable**, so a registration can't point it elsewhere. `CoreConfig.eulaFile` is null in `forWorkspace` (tests), so a developer's own acceptance never changes test results.
6. **`acceptEula=true` stays** for owners who prefer to answer per server, and for machines without the one-time file.

## Consequences

- After one owner action on a machine, Paper and Fabric sessions on it have no EULA wait. Minestom is unchanged (no EULA).
- The check stops tools and scripts from accepting; it does not stop someone who deliberately forges a terminal or writes the file by hand. The same was always true of `eula.txt`, and the project rules forbid an AI from doing either.
- `msc-bench` still reads its own untracked `bench.local.json`; it could read `eula.json` later (follow-up, not part of this change).
- The owner's action on Jeremiah's PC: after the next `installMsc`, run the line in `~/.msc/core/eula-accept.txt` in a terminal, if he agrees.

## Amendment, 2026-11-12: opt-in on the install and run commands

Jeremiah asked for EULA acceptance to be built into the commands, and approved MSC terms of use for it. Treating the mere install or use of MSC as acceptance was rejected, because the EULA is an agreement between each server operator and Mojang. An explicit opt-in that the user gives was accepted instead:

1. **`installMsc -Pmsc.acceptEula=true`** writes `~/.msc/eula.json` (`how`: the install flag) and prints the notice.
2. **`acceptEula=true` on `server_setup` and the new `acceptEula` option on `server_start`** write `eula=true` for that server and also record the acceptance for the machine (`how`: the user's yes passed as acceptEula). The first time, the result carries a notice for the AI to pass on, with the EULA and [TERMS.md](../../../TERMS.md) links. This replaces decision 4's "no tool argument creates it": the argument already stood for the user's own yes, and the AI instructions still forbid passing it without one.
3. **[TERMS.md](../../../TERMS.md)** says what turning acceptance on confirms: the user read and accepts Mojang's EULA, MSC may write `eula=true` for their servers on that machine from then on, and they are responsible for following it. The README links it. It is not legal advice, and it has not been reviewed by a lawyer.
4. Nothing changes for the default: with no flag and no file, every server still asks.

## Amendment, 2026-11-14: the three writers of `eula.json` (#383)

The 2026-11-12 amendment changed who may write the file, but replaced only decision 4's "no tool argument creates it", so decisions 2 and 3 went on saying that `Main eula accept` is the only writer and that `MachineEula.write` is package-private and called only from `EulaCommand`. That is not the code. This amendment replaces decision 2's "Only one command writes it", decision 3's last sentence and decision 4's "No MCP tool and no tool argument writes `eula.json`" with the following. The original text above is kept as decided.

1. **Exactly three ways write `~/.msc/eula.json`, each an explicit opt-in by the owner:**
   - **The terminal command**, `Main eula accept`, after the owner types `yes` in an interactive terminal (`EulaCommand` calls `MachineEula.write` with `MachineEula.VIA_TERMINAL`). Decision 3's terminal check still applies to it.
   - **`installMsc -Pmsc.acceptEula=true`**: the install task in `msc-core/build.gradle.kts` writes the file itself (`how`: the install flag) when it does not exist yet. It still never runs the `eula accept` line it prints.
   - **The first `acceptEula=true` on `server_setup` or `server_start`**: both call `EulaPrompts.recordForMachine`, which calls `MachineEula.write` with `MachineEula.VIA_TOOL` when the file is missing. `dev_cycle` takes no `acceptEula` and writes nothing. `project_scaffold`'s `acceptEula` only fills the `paper-server` template's `eula.txt` and is not kept for the machine.
2. *Amended by [ADR 0036](0036-guards-by-construction-and-behaviour.md): the write is now `MachineEula.recordAcceptance` on the `MachineEula` object `CoreConfig.machineEula()` holds (`MachineEula.none()` in tests, where decision 5 said null); the file's path never leaves the class, and `EulaOptInBehaviourTest` runs every tool to show none accepts without an opt-in.* **`MachineEula.write` is public**, because `EulaPrompts` lives in another package (`core.tools`). Its callers must pass only an agreement the owner gave. `EulaWritersTest` (msc-core's `repoChecks`) finds every production call site that writes the file and fails when the set differs from the three above; a change to it must update this ADR and `tools.md`.
3. **Unchanged:** installing or using MSC alone never accepts Mojang's EULA. With no flag, no `acceptEula=true` and no file, every Paper, Purpur, Fabric, Quilt, NeoForge and Forge server still asks. Nothing in CI, the bench, the templates or any default writes the file, and the AI instructions still forbid passing `acceptEula=true` without the user's own yes.
4. **Consequences above that this changes:** the check in decision 3 stops a script or an AI from accepting through the terminal command only; a tool call with `acceptEula=true` records the acceptance it stands for. `msc-bench` no longer reads `bench.local.json`: it reads `~/.msc/eula.json` and never writes it ([ADR 0004](0004-bench.md)).
