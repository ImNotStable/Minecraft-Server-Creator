# ADR 0047: Windows arguments and the prompt on stdin

Status: accepted, 2026-11-15, decided by Claude (#624).

## Context

On Windows a program gets one command line, not a list of arguments, and splits it itself. Java builds that line
from the `ProcessBuilder` list. In its default mode (`jdk.lang.Process.allowAmbiguousCommands` unset, JDK 25's
`VERIFICATION_LEGACY`) it wraps an argument with a blank in quotes and appends it verbatim, so a `"` inside it is not
escaped; an argument that already starts and ends with `"`, or has no blank, goes in as it is. The program then
splits the line by the C runtime's rules (`CommandLineToArgvW`), and the first inner quote ends the quoted part.

So `game-sky-sprint`'s prompt (`... into "Sky Sprint", a short timed race.`), filled into `claude -p {prompt}`,
reached `claude.exe` as a prompt cut at "into Sky" and a stray argument holding the rest of the task. Java's strict
mode (`allowAmbiguousCommands=false`) escapes inner quotes for an `.exe`, but throws "Argument has embedded quote"
for a `.cmd` or `.bat`, such as npm's `claude.cmd`. The same holds for every argument the bench starts a program with
from user text: a mode's `extraArgs`, a `command` check's `run`, `server.command`.

A `.cmd` or `.bat` runs in cmd.exe, which reads the line by its own rules before the script passes `%*` on to the
real program (node, java): `%NAME%` is expanded even inside quotes, `& | < > ( ) ^` act outside them, and a line
break ends the command whatever the quoting. No command line carries a multi-line prompt through such a launcher.

`claude -p` reads its prompt from stdin when it is given none (Claude Code's CLI reference, "Process piped content";
`claude -p < /dev/null` answers "Input must be provided either through stdin or as a prompt argument when using
--print"; Claude Code 2.1.325 run against a local capture endpoint sent the whole Sky Sprint prompt, quotes and line
breaks, from stdin).

## Decision

Both: the prompt leaves the command line, and every command line is built in one place.

1. **The prompt goes over stdin.** The shipped and default `agent.command` drop `{prompt}`. When neither
   `agent.command` nor the mode's `extraArgs` or `env` holds `{prompt}`, the runner gives the agent the attempt's
   `prompt.md` as stdin; otherwise it fills `{prompt}` as before and stdin stays the null device. A config without
   `{prompt}` is no longer refused. This removes the largest and least controlled argument, which holds line breaks
   no cmd.exe launcher can pass, and needs no quoting at all.
2. **One Windows encoder for every start.** `WindowsCommandLine.prepare` runs inside `ChildProcesses.start` and a
   check context's default start, the doors every bench process goes through (agent, server, `command` checks, the
   `Win32_Process` query). On Windows it rewrites the builder's list so the JDK's default join yields the intended
   line:
   - an argument with no blank and no quote stays as it is; any other is quoted by the C runtime's rules
     (`2n+1` backslashes before an inner quote, `2n` before the closing one), so it starts and ends with `"` and the
     JDK appends it untouched;
   - a program ending in `.cmd` or `.bat` becomes `cmd.exe /d /v:off /s /c ""<script>" <args>"`, each argument quoted
     as above and every cmd.exe metacharacter (`" % ! ^ & | < > ( )`) escaped with `^` twice: once for this cmd.exe,
     once for the script's own line that expands `%*`, as cross-spawn does for npm launchers. With every quote escaped
     cmd.exe never enters a quoted part, so every escape works. cmd.exe expands variables before it reads a caret,
     and besides `%NAME%` it takes `%NAME:old=new%` and `%NAME:~start,length%`, whose name ends at the `:`; so the
     character after each `%` is escaped too (`%PATH:a=b%` becomes `^^^%^^PATH:a=b^^^%`), and every name cmd.exe can
     read after a `%` starts with `^`. cmd.exe defines no such variable, and `prepare` removes any from a script's
     environment, so no argument expands one (#630; the first version escaped only the `%` itself, which broke
     `%NAME%` but let `%TEMP:\=/%` expand). An argument with a line break is refused for a script, naming it, instead
     of cut;
   - Java's strict mode would quote the encoded arguments a second time, so with it set every start is refused,
     naming the property.
   Off Windows the list is left as it is: Linux and macOS pass arguments as a list.

Tests run on any OS: the encoded list is joined as JDK 25's legacy `createCommandLine` joins it and split again with
`ProcessArguments.splitWindows`; for a script, cmd.exe's variable pass (`%NAME%` and both `:` forms, in an environment
that defines every name not starting with `^`) and its caret pass, then the launcher's caret pass, are modelled before
the split. Inputs cover quotes, backslashes before quotes, trailing backslashes, empty strings, blanks, `%` (with the
`:` forms), `^`, `!` and the redirection characters, and every shipped prompt with each preamble.

## Consequences

- The agent sees the same prompt on every OS, and `prompt.md` is exactly what it read.
- A `.cmd`/`.bat` launcher works with quoted arguments, but the double escape assumes it forwards them with `%*` (npm
  shims, `gradlew.bat`, Gradle start scripts); a script that reads `%1` itself sees caret-escaped text.
- A bare `claude` still needs `claude.exe` on the PATH (Windows does not try `.cmd` for it); with the npm package write
  `claude.cmd`.
- The encoding relies on the JDK's default join passing an argument that starts and ends with a quote through
  verbatim. The test models that join from JDK 25's source, so a JDK upgrade should recheck
  `ProcessImpl.createCommandLine` on Windows.
- A `.cmd`/`.bat` launcher and what it starts do not see an environment variable whose name starts with `^`.
- Not verified on a Windows machine yet: the tests model the JDK join and cmd.exe; the first Windows run of
  `game-sky-sprint` is the proof.
