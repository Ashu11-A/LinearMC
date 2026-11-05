# Removed server legs (archive only)

As of **v1.1.4**, LinearMC supports only:

- **Folia 26.1.2**
- **CanvasMC 26.3**
- **Horizon plugin 1.0.0** on Paper/Folia/CanvasMC 26.1 through 26.3

CanvasMC 26.1.2 and 26.2 are removed from the active build matrix. Their
legs and pins are kept here **unchanged** so the history is not lost:

- `26.1.x/canvasmc/` and `26.1.x/upstream.canvas.properties`
- `26.2/` (whole line: Canvas and Folia pins, although only the Canvas leg
  had patch content)

Nothing in this directory is staged, built or tested. Do not add it back to
the matrix without porting and re-validating the patches against a current
upstream build. The last release that shipped these legs was **v1.1.3**;
those assets remain on GitHub.
