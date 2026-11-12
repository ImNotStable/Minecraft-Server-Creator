# ADR 0020: One Minecraft EULA acceptance per machine

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions. Roadmap item N1.

## Context

Paper and Fabric run Mojang's server, which starts only with `eula=true` in `eula.txt`. Until now every new dev server needed the owner's own "yes", relayed through the AI (`server_setup acceptEula=true`). In the Lumen Siege playtest that wait was 15 of 26 minutes, and every Paper or Fabric session paid it again. The owner may not be at the keyboard when a thread reaches `server_setup`, and the agreement must still be theirs: no tool, flag or default may accept it for them, and an acceptance must never land in a tracked file (as `msc-bench/bench.local.json` stays untracked).

## Decision

1. **The owner records the agreement once per machine and user, in `~/.msc/eula.json`.** It holds the EULA URL, `accepted: true`, the OS user name, the host name, the time and how it was given. It lives in the home folder, outside any repo, workspace and the install folder (installs live in `~/.msc/core/msc-core-*` and old ones are pruned; `eula.json` is one level up), so it can't be committed or wiped by a reinstall.
2. **Only one command writes it: `Main eula accept`**, run by the owner as `"<java>" -cp "~/.msc/core/msc-core-<version>-<time>/lib/*" io.github.imnotstable.msc.core.Main eula accept` (the newest install, ADR 0021). `installMsc` prints that line and saves it in `~/.msc/core/eula-accept.txt`, a fixed path that each install rewrites to point at its own jars; it never runs it. For a release-zip install, the core's own EULA messages give the same line for the install they run from. The command shows the EULA link and what agreeing means, then reads the answer from the console and writes the file only on `yes`. `eula status` shows it, `eula revoke` deletes it.
3. **It refuses without an interactive terminal** (`System.console()` present and `isTerminal()`). Claude Code's Bash tool, Gradle `run`, CI and any pipe have no terminal, so an AI or script that runs the command gets "Refused" and nothing is written. `MachineEula.write` is package-private and called only from `EulaCommand`.
4. **MSC tools only read it.** No MCP tool and no tool argument writes `eula.json`. When it is present:
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
