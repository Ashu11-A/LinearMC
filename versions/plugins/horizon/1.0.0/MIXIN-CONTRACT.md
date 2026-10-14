# Mixin contract

Every plugin patch selector is traced to a verified server method.
Verify mechanically before release. Any drift fails the gate.

Current proven trace, bytecode verified on the dev bundle:

| Plugin patch purpose | Server method it targets | Notes |
| --- | --- | --- |
| Storage class patch marker | Region storage class | Pristine cache shape |
| Folder and sync field shadows | Region storage folder, info, and sync fields | Needs access transformer entries |
| Shift constant shadow | Region coordinate shift constant | Value five |
| File name override | Region file naming routine | Changes extension from old to new |
| Single creation redirect | Region file creation routine | Only one file creation site exists, covers both create and exists paths, restart re-reads new files through it |
| Close hook | Region storage close routine | Runs at tail |
| Flush hook | Region storage flush routine | Runs at head |
| Folder field shadow | Region storage folder field | Confirmed in field list |
| Access wideners | Build access entries | Replaces access transformer entries |
| Format mapping | Local format policy | Server has no paper region format type, so mapping lives in plugin policy with an owned factory fork |

Per-MC retrace (HORIZON_REF `31d1a1b7`; build pin in
`versions/plugins/horizon/upstream.horizon.properties`, per-bundle test pins
in `upstream.horizon-<mc>.properties`):

| Selector | Target method | 26.1.2 | 26.2 | 26.3 |
| --- | --- | --- | --- | --- |
| `getRegionFileName` overwrite | Region file naming routine | verified | PENDING | verified |
| Create-path redirect | Region file creation routine | verified | PENDING | verified |
| Exists-path redirect | Exists/read/write creation site | verified | PENDING | verified |
| `close` tail inject | Region storage close routine | verified | PENDING | verified |
| `flush` head inject | Region storage flush routine | verified | PENDING | verified |
| Folder / shift shadows | Region storage folder, info, sync fields; region coordinate shift | verified | PENDING | verified |

26.1.2 and 26.3 verified by `javap` against the dev bundles plus the
reflection patcher check (`MixinDescriptorsTest#mixinTargetsExistInThisBundle`,
run per bundle in the `test.yml` matrix). 26.2 stays PENDING until that
matrix goes green there; selectors are `defaultRequire`, so a miss fails
the boot loudly instead of silently.

Retired selectors, kept for provenance only:

- The Moonrise exists-path redirect is deleted. That routine only delegates now.
- Redirects that named the constructor descriptor were dead on arrival. The patch point takes the class, not the full constructor.
- Only the file name override had ever applied in early boots, which wrote Anvil content under new names. Caught by the superblock check.

Packaging notes proven in testing:

- The compression library must be embedded in the jar, otherwise the first new-format write fails at runtime, which also proves the redirect engages.
- The plugin descriptor follows the real schema with name, version, description, authors, mixins, dependencies, and wideners.
- The paper half loads through the plugin flag, but the mixin jar must also sit in the plugins folder for discovery.

For behavior and settings see docs/architecture.md and docs/configuration.md.
