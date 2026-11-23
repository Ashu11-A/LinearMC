# Tag scheme

LinearMC release tags use the `v<base>-release.<N>` form from v1.1.4 onward.
The base version names the patch set, and `N` counts releases of that set
(a build against a new upstream, or a rebuild).

| Tag | Meaning |
| --- | --- |
| `v1.1.4-release.1` | First release of the v1.1.4 patch set. |
| `v1.1.4-release.2` | A later rebuild/release of the same patch set. |

The older tags `v1.0.0`, `v1.1.0`, `v1.1.1`, `v1.1.2` and `v1.1.3` stay
exactly as they are; nobody aliases, moves or deletes them. Don't create a new
plain `vX.Y.Z` tag. A release tag is always `vX.Y.Z-release.N`.

## Asset names

Asset filenames carry the base version (`1.1.4`), and the tag carries the
release counter (`v1.1.4-release.1`). The release body writes the upstream
build as `#N`, but GitHub rewrites a literal `#` in an asset filename, so the
filename spells it `+build.N`. GitHub keeps the `+`, which is semver build
metadata.

Examples:

- `linearmc-folia-26.3-1.3.0+build.8.jar`
- `linearmc-canvas-26.3-1.1.4+build.1010.jar`
- `linearmc-horizon-26.1-26.3-1.1.4+build.74.jar`
