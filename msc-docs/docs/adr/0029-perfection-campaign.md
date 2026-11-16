# ADR 0029: The two-day perfection campaign

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions, refining his brief of
2026-11-12 23:21Z.

## Context

Jeremiah gave two days of usage to bring all seven platforms to "perfection" and to remove repeated, redundant and
slow code: sub-agents audit the codebase in pieces and file GitHub issues until a new auditor finds nothing twice,
then sub-agents fix each piece. He asked for the plan to be refined. Until now the repository had no issues; work
was tracked in the roadmap and playtest findings. GitHub Actions is still billing-blocked.

## Decisions

1. **"Perfect" is a checklist**, in [campaign-2026-11.md](../campaign-2026-11.md): two clean audits per piece, all
   campaign issues closed, a green seven-platform matrix on a real PC, green builds on Linux and Windows, a bench
   that is no worse, docs that match.
2. **Fourteen pieces**, thirteen by path and one cross-cutting (duplication between modules, platform parity).
3. **Auditors report, a second agent files.** The verifier-filer re-reads the code for every finding, drops what it
   cannot confirm, and dedupes against every campaign issue, open or closed. Only confirmed findings count toward
   "found something".
4. **A written bar for a finding** (six kinds, three severities, file and line required). Taste is not filed.
5. **Stop rule**: two consecutive rounds with no confirmed new finding; at most eight rounds per piece. After a
   piece's fixes merge, one more audit must come back clean.
6. **Fixing is rolling**: a piece is fixed as soon as its audit closes. One PR per piece batch of about six issues,
   a test per fix, a full build before the PR, merged by the integrator thread after a local build.
7. **Ownership is relaxed for the campaign**: fix agents may change shared files (`settings.gradle.kts`,
   `gradle/`, `build-logic/`, `msc-protocol/`, `CLAUDE.md`) when an issue needs it. The integrator orders such PRs.
   The rule in `CLAUDE.md` returns when the campaign ends.
8. **GitHub issues are the work list** for the campaign, with labels `campaign`, `piece:*`, `kind:*`, `sev:*`, and
   one tracking issue that logs every audit round. The roadmap stays the place for features.
9. **Real servers run on the PC**, twice: a baseline matrix at the start and a second run after the fixes.
   graphify and ponytail are PC-only, so cloud auditors work without them.
10. **No new features** in the campaign except what a `kind:parity` issue needs to make platforms equal.

## Consequences

- Up to about 200 short agent runs for audits and about 30 for fixes; the verifier doubles audit cost but keeps false
  issues away from fix agents.
- The stop rule can end a piece with real but unfound defects; the PC matrix and the bench are the independent check.
- Many small PRs land in two days; main must stay green between them, so the integrator merges one at a time.
- The eight-round cap and the cut order mean "perfection" may end with open `sev:3` issues; they are listed in the
  campaign file, not hidden.

## Amendment, 2026-11-13: no round cap

The cap of eight rounds per piece (decision 5, and the matching consequence above) is lifted. A piece that is not
clean twice in a row gets confirm rounds until it is, or until the campaign ends on 2026-11-14 23:00Z. Reason: the
round 8 sweep still found 15 confirmed, real defects in eight pieces, so the cap would have stopped audits that were
still finding bugs, and the owner's stop rule is two clean audits in a row, not a round count. A piece still not
clean when the campaign ends is listed in [campaign-2026-11.md](../campaign-2026-11.md) with its open issues. The
original text above is kept as decided.
