# Tag and alias scheme

LinearMC uses the project version as the release tag. Patch-set identity is
the base version; complementary rebuilds add a release counter.

| Tag form | Meaning |
| --- | --- |
| `vX.Y.Z` | First release of a patch set. Equivalent to `vX.Y.Z-release.1`. |
| `vX.Y.Z-release.1` | Explicit alias of the first release. |
| `vX.Y.Z-release.N` (N >= 2) | Rebuild with unchanged Linear patches, usually to follow a new upstream server build. |

Examples:

- `v1.1.3` = `v1.1.3-release.1`.
- `v1.1.3-release.2` would be a rebuild of the v1.1.3 patch set against a
  newer upstream build.
- `v1.1.4` is the first release of the next patch set (CanvasMC 26.3 #1010,
  OpenHFT hash delegation, supported-matrix narrowing).

## Historical aliases

The past is not rewritten. The existing tags remain the canonical download
targets, and these aliases point at the same commits:

| Alias | Points at | Original tag |
| --- | --- | --- |
| `v1.0.0-release.1` | `10766ce` | `v1.0.0` |
| `v1.1.0-release.1` | `35ccc51` | `v1.1.0` |
| `v1.1.1-release.1` | `479da3a` | `v1.1.1` |
| `v1.1.2-release.1` | `4d5b0a0` | `v1.1.2` |
| `v1.1.3-release.1` | `a44530f` | `v1.1.3` |

The aliases are tag-only: the GitHub releases remain attached to the original
tags, so existing download URLs keep working. Do not delete or force-move an
old tag without a migration note.

## Asset names

`+` is semver build metadata and is preserved by GitHub. The release body
uses the `#N` form, but literal `#` in an asset filename is rewritten by
GitHub, so the filename encodes it as `+build.N`.

Examples:

- `linearmc-folia-26.1.2-1.1.4+build.8.jar`
- `linearmc-canvas-26.3-1.1.4+build.1010.jar`
- `linearmc-horizon-26.1-26.3-1.1.4+build.74.jar`
