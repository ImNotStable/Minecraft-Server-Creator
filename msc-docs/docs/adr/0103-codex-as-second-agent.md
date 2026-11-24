# ADR 0103: Codex as the second agent, and the failing count where it is read

Status: accepted, 2026-11-24. Decided by Claude; the agent and its effort were Jeremiah's choice ("You can utilize
Codex as well Terra or Sol", "On medium").

## Codex in the Shattered Meridian loop

The loop (ADR 0094-0098) ran Antigravity's CLI (Gemini 4 Neon XHigh); run 11 stopped at its daily quota. Codex now
runs alongside it:

- Binary: the `codex` CLI bundled in the Codex app (`...\WindowsApps\OpenAI.Codex_<version>\app\resources\codex.exe`,
  0.171). The standalone CLI under `%LOCALAPPDATA%\OpenAI\Codex\bin` (0.129) cannot read Jeremiah's
  `~/.codex/config.toml` (`service_tier = "default"` is newer than it).
- `codex exec --json --skip-git-repo-check -m gpt-6.2-sol -c model_reasoning_effort="medium"`, MSC added with
  `-c mcp_servers.msc.command/args/env` pointing at the project's own launcher, from the project folder. Jeremiah's
  config already sets `approval_policy = "never"` and `sandbox_mode = "danger-full-access"`; no flag overrides them.
- Prompt: the same task as the Gemini runs, naming the design file in words (`@file` is Gemini's syntax).
- Schedule: Gemini and Codex take turns, and Codex runs when Gemini reports "Individual quota reached".

## What run 13 (Codex) did

37 minutes, 227 MSC calls: 52 `build_batch`, 37 `world_summary`, 33 `world_render`, 18 `player_test`, 19
`pack_asset`. It read every guide page, built one generated dimension for all six regions, and tested the game
hardest of any run (story, railway stages, trading, settlements, four expeditions to completion). Its report was
candid about scope ("Four of the planned 18 dungeons are built"), but 13 of its 14 review regions still failed (bare
base, floating blocks, plain wall tops, stacked leaves, dark rooms) and the report did not say so. It saw the failing
list four times, at the end of `dev_cycle` output after the build log.

## Decision

- `build_batch` results end with one line, "N reviews still failing (msc_status lists them): the work is not done",
  whenever any are open.
- `dev_cycle` puts the failing list first, before the build and test output.

## Not done

- Codex on Terra, and on higher effort, are not tried yet.
