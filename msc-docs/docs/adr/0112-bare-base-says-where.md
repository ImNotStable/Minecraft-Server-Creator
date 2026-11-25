# ADR 0112: A bare base says where it is and what to try

Status: accepted, 2026-11-25. Decided by Claude; Jeremiah asked whether MSC was being improved to prevent run 34's
failures.

## What run 34 did

Run 34 (Codex, GPT-6.2-Astra, Saltmarsh Reach) ended with 9 of its 22 review regions failing, 12 on re-review, most on
"bare base". 45 of its reviews named bare base and the fix text named `trim at=base`; it used trim zero times. After
each bare-base failure it ran the building's whole script again (192 lines of `fill` and `set`, the same walls on the
same ground), so the number did not move. It wrote about 30,000 `fill` and 7,000 `set` lines in the run and never used
`decorate` on the ground either. The fix said what, not where, and rebuilding looked like fixing.

## Decision

- The bare-base fix line now says where the bare line runs (its x, z and y range) and gives a starting point with
  those coordinates: `trim x1,y,z1 x2,y+1,z2 <a stair or slab of your palette> at=base`, then
  `decorate ... <your plants> on=ground` one block wider, and says that rebuilding the walls does not change the line.
  The blocks stay the agent's choice; the steps are the general primitives (ADR 0106).
- The build guide's "done means" says to change exactly what a failing review names, where it names it, and that
  rerunning a build's first script repeats the same numbers.

## Not done

- Other fix lines (plain wall tops, flat roofs) still say what without where; follow if runs show the same pattern.
