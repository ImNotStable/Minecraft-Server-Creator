# ADR 0061: Windows CI failures are Windows behaviour, not the runner's account

Status: accepted, 2026-11-20, decided by Claude for the owner (who approved making the Windows runner pass).

## Context

ADR 0056 left the Windows job on Flux's Gitea runner red, with the failures put down to the runner: a service
under LocalSystem, a different code page, a different process view. The plan was to force UTF-8 with
`JAVA_TOOL_OPTIONS` and run the service under the owner's Windows account.

Running the failing tests on Flux as the owner, outside the service, failed the same way. None of them was the
account or the encoding:

- Path refusals quote `InvalidPathException`'s reason, which is Linux's `Nul character not allowed` and Windows'
  `Illegal char <\u0000>`. Windows also refuses a trailing space and a line break in a path, so `" "` was refused
  as no path before the blank rule could name it.
- `println` ends lines with `\r\n`, which the hidden-character check read as a hidden `\r`.
- Windows file locks are mandatory: a snapshot could not read a lock file another process held, and a temp folder
  could not be deleted while a detached server still ran in it.
- A deploy record written by hand with `C:\...` as a properties key reads as key `C`.
- `ProcessHandle.Info` shows no arguments on Windows, and the bench proves ownership only where it sees a process's
  environment (#595), so two leftover-kill tests cannot hold there.

## Decision

1. Keep the runner as it is: LocalSystem, no `JAVA_TOOL_OPTIONS`. `JAVA_TOOL_OPTIONS` would also make every JVM
   print a line to stderr, which tests that read a child's output would have to skip.
2. Fix the tests, and the one code path (a blank path is left to the blank rule), so they hold on both systems: the
   expected path reason comes from this system's `Path.of`, lock files are sized rather than read, records are
   written escaped, detached servers are stopped, and fake agents are found by `ProcessArguments`. Tests of
   environment-based ownership assume `AttemptProcesses.canProve()`, as the others already did.
3. (2026-11-21, after the first full run on the runner) Fake agents are found by their command line or, where
   Windows shows none, by `ProcessArguments`; matching only whole arguments missed them on Linux.
4. On Windows only, `msc.java-conventions` sets JUnit's temp-dir deletion strategy to `IgnoreFailures`. Tests run
   concurrently, and a child one test starts can inherit a handle to a file in another test's temp folder (Windows
   process creation hands inheritable handles to every child started at that moment) and hold it until its own
   test ends. That is a property of the test JVM, not a leak in the code under test, and it failed a different set
   of tests on every run. Linux keeps strict deletion, so a test that really leaves a process behind still fails
   there.
5. Windows refuses a full listen backlog, so a test holding a port it never accepts on keeps room for every probe;
   a symbolic-link scenario is skipped where Windows will not make links (no Developer Mode or privilege).

## Consequences

- Windows CI can gate merges once it passes on `main` (ADR 0056 point 1).
- A new test that compares an exception's text, reads a file another process locks, or counts processes by
  command line must allow for Windows.
- Temp folders a Windows test run could not delete stay behind under the runner's temp folder; they are logged.
  A leaked process is caught by the Linux job, not the Windows one.
