# Removed server legs (archive only)

These legs are no longer built. Their patches and pins are kept here
**unchanged** so the history is not lost:

- `26.1.x/`: the Folia 26.1.x fork leg (archived in v1.3.0; Folia 26.1.2 hosts
  now use the Horizon plugin), plus the CanvasMC 26.1.2 leg and
  `26.1.x/upstream.canvas.properties` (archived in v1.1.4).
- `26.2/`: the whole 26.2 line, with Canvas and Folia pins, although only the
  Canvas leg had patch content (archived in v1.1.4).

The active matrix is Folia 26.3, CanvasMC 26.3, and the Horizon plugin 1.0.0
on Paper, Folia and CanvasMC 26.1 through 26.3.

Nothing in this directory is staged, built or tested. Don't add a leg back to
the matrix without porting its patches and validating them again against a
current upstream build. The last release that shipped the CanvasMC 26.1.2 and
26.2 legs was **v1.1.3**, and its assets are still on GitHub.
