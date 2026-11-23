# ADR 0080: Shaping steps in build_batch

Status: accepted, 2026-11-23. Decided by Claude, filling Jeremiah's gap list ("no organic brushes like blob, smooth or
erode", "no real rivers, caves or erosion") and the plugin survey's second recommendation: reimplement the ideas of the
brush plugins natively, since goBrush, goPaint and VoxelSniper need a player clicking and cannot be driven by an agent.

## Decision

Seven `build_batch` steps that reshape what the world and the earlier steps already hold, on the canvas like every step
(one undo id, dry run, `replace=`), written from the ideas, not from any plugin's code:

- `smooth`: column heights averaged over a radius, several passes, each column keeping its top block on top; the box's
  rim eases back to the land outside, so no seam.
- `erode mode=melt|fill|smooth`: a cellular pass by open or solid faces (the idea of an erosion brush): melt takes
  spikes and sharp edges off, fill closes pits with the neighbours' block (the one below counts double), smooth does
  both.
- `blend`: each block takes its 3x3x3 neighbourhood's most common block; `shape=false` blends materials only.
- `paint`: the exposed skin by face (top, side, all), depth and the ground's slope in degrees, so cliffs get stone and
  flats get grass without the agent tracing them.
- `river`: the channel follows `path`'s curve, but its water holds one level that only falls downstream, from the lowest
  ground seen so far, so a river on a slope never becomes a water staircase; banks slope down to it over `bank=`.
- `tunnel`: a ball dragged along a 3D Catmull-Rom curve, its radius breathing with noise.
- `cave`: two crossing bands of 3D noise carve connected tubes and rooms through the solid ground of a box, leaving
  `roof=` blocks under the surface.

The tool description gets two lines for all seven (the listing budget rises by 200), the details are in `guide=build`.

## Consequences

- Terrain made with `terrain`, a generated world or by hand can be finished without a plugin or a script.
- The steps read the box as it is; a box much larger than the work is read and written whole, so keep boxes tight.
