# ADR 0025: Pull requests run the Ubuntu build only

Status: accepted, 2026-11-12. Decided by Claude (integrator and coordinator) under Jeremiah's hand-over of build decisions.

## Context

The repository is private, so GitHub Actions minutes are billed, and Windows minutes count double. After ADR 0012's Windows build and ADR 0022's end-to-end job, every pull request push ran four jobs (Ubuntu and Windows build, Ubuntu and Windows e2e), and round 2 had six PRs open at once. At about 09:54Z on 2026-11-12 Actions stopped starting jobs ("recent account payments have failed or your spending limit needs to be increased"), so no PR could get CI.

## Decisions

1. **Pull requests run one job: `Build and test (ubuntu-latest)`.** It runs `./gradlew build`, which holds every unit test.
2. **The full set runs elsewhere.** The Windows build and the Minestom end-to-end job (Linux and Windows) run on pushes to `main`, nightly at 03:17 UTC, by hand (`workflow_dispatch`), and on a pull request labelled `full-ci`. Adding the label starts a full run; other labels start nothing.
3. **Kept from before:** a newer push cancels the older run on the same branch (not on `main`), docs-only changes skip CI, and `setup-gradle` caches dependencies and the build cache, written only from `main`.
4. **Use `full-ci` on PRs that touch** OS-specific code (paths, processes, ports, file locking, line endings), the agents, the core's MCP layer, `dev_cycle`, templates or the CI workflow itself.

## Consequences

- A typical PR costs one Linux job instead of two Linux and two Windows jobs (about a sixth of the billed minutes).
- A Windows-only or e2e-only break can reach `main` and is then caught by `main`'s own run or the nightly run, not by the PR. The integrator fixes it in a follow-up PR the same way as any red `main`.
- While Actions cannot run at all, the integrator verifies each PR with a local build and tests on top of current `main` (and on the PC for Windows or Paper when it matters) before merging.
- The GitHub-managed "Automatic Dependency Submission" workflow is not in this repository and is not changed here.
