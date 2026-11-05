# Tag scheme

LinearMC release tags use the `v<base>-release.<N>` form from v1.1.4 onward.
The base version identifies the patch set; `N` identifies the release of that
set (an upstream companion build or a rebuild).

| Tag | Meaning |
| --- | --- |
| `v1.1.4-release.1` | First release of the v1.1.4 patch set. |
| `v1.1.4-release.2` | A later rebuild/release of the same patch set. |

Historical tags `v1.0.0`, `v1.1.0`, `v1.1.1`, `v1.1.2` and `v1.1.3` are left
exactly as they were. They are not aliased, moved or deleted. Do not create a
new plain `vX.Y.Z` tag: the release tag is always `vX.Y.Z-release.N`.

## Asset names

Asset filenames carry the base version (`1.1.4`), not the release counter;
the tag carries the counter (`v1.1.4-release.1`). `+` is semver build
metadata and is preserved by GitHub. The release body uses the `#N` form, but
literal `#` in an asset filename is rewritten by GitHub, so the filename
encodes it as `+build.N`.

Examples:

- `linearmc-folia-26.1.2-1.1.4+build.8.jar`
- `linearmc-canvas-26.3-1.1.4+build.1010.jar`
- `linearmc-horizon-26.1-26.3-1.1.4+build.74.jar`
