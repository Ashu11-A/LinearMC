# Linear multi-format requirements: V1 / V2 / V3 / B_LINEAR / PUMPKIN

Status: normative for the five-format work. Sources: DivineMC
`region/linear/` (cloned 2026-10-02, sparse) and Pumpkin
`crates/pumpkin-world/src/chunk/format/linear.rs` (cloned 2026-10-02,
sparse). All integers big-endian everywhere.

Shared magic: `0xC3FF13183CCA9D9A` (`C3 FF 13 18 3C CA 9D 9A`), 8-byte footer
repeats the header magic on every format.

## 1. On-disk layouts (normative)

### 1.1 V1 — single zstd blob. Header 32 B, wires 1 and 2 (alias)

| off | size | field |
|---|---|---|
| 0 | 8 | `long SUPERBLOCK` |
| 8 | 1 | `byte version` (writes 2, reads 1..2) |
| 9 | 8 | `long timestamp` (seconds, widened) |
| 17 | 1 | `byte compressionLevel` 1..22 |
| 18 | 2 | `short chunkCount` (non-empty, <= 1024) |
| 20 | 4 | `int dataCount` = compressed length |
| 24 | 8 | `long 0` reserved |
| 32 | dataCount | one zstd frame (checksum) |
| 32+dataCount | 8 | `SUPERBLOCK` footer |

Payload: table `1024 x (int uncompressedSize + int timestamp)`, then
concatenated raw chunk bytes in slot order (absent slots contribute 0
bytes). Hash: none (zstd checksum only). DivineMC `parse` does NOT
validate the footer value and resets timestamps to now; we preserve
stored timestamps (documented divergence) and validate the footer.

### 1.2 V2 — bucketed, wire 3. Header 155 B + features

`8 magic + 1 ver(3) + 8 timestamp + 1 grid + 4 regionX + 4 regionZ +
128 bitmap + features`, where features = repeats of
`[u8 nameLen][name][u32 value]`, terminated by `0x00` (empty = 1 byte).
Bitmap is MSB-first: `bit j of byte i = (b >> (7-j)) & 1`; informational
on read (presence = table size != 0).

Table: `grid x grid` entries of 13 B: `int bucketSize` (0 = absent) +
`byte bucketCompressionLevel` + `long bucketHash` (XXH64 seed 0 over the
**compressed** bucket bytes, 0 when absent).

Bucket body: one zstd frame per bucket; decompressed stream is a
sequence over the bucket's slots: present =
`int (len+8)` + `long timestamp` + `len` NBT bytes; absent =
`int 0` + `long timestamp` (12 B slot header — the reader must consume
the timestamp even for absent slots).

Footer 8 B `SUPERBLOCK`, validated. Hash mismatch aborts (fail high).

### 1.3 V3 — bucketed, wire 4. Header 10 B

`8 magic + 1 ver(4) + 1 grid`. No timestamp, coords, bitmap, or
features. Table: `grid x grid` entries of 12 B: `int bucketSize` +
`long bucketHash` (same XXH64). Bucket bodies and footer identical to
V2. The level is a write-time choice only and is not stored.

### 1.4 PUMPKIN — bucketed, wire 2, `.linear` extension

Superblock 26 B: `8 magic + 1 ver(2) + 8 newest-timestamp +
1 grid + 4 regionX + 4 regionZ`. Then 128 B bitmap (**LSB-first**:
`bit = i % 8` — opposite of V2), features in the same
`[u8 len][key][u32 val] 0x00`-terminated encoding, table
`grid x grid` of 13 B (`u32 size` + `i8 level`, always 1, ignored on
read + `u64 xxhash` seed 0), one `ruzstd Fastest` frame per bucket,
footer 8 B validated.

Bucket body differs from V2/V3 in one field: slots are
`u32 size` + `u64 timestamp` + `size` NBT bytes, i.e. the stored length
is the raw NBT length (**not** len+8). Absent slots are `u32 0` (+ 8 B
timestamp). Grid default 2. Hash mismatch skips the bucket and
continues upstream; we fail high instead (documented divergence).
Staging writes `tmp + rename` with no fsync; we use `tmp + fsync +
atomic move`.

### 1.5 B_LINEAR — buffered tier (unchanged, separate from V1/V2/V3)

`.b_linear` master (own magic, 18 B header, single checksummed zstd
frame of 1024 inner envelopes) + `.b_linear.swp` swap while open/dirty
(own magic, sector table, per-chunk LZ4). Never wire 4. WAL-at-rest:
clean close prunes the swap (plus `.tmp`/`.swp.tmp` staging) so steady
state is the master alone; the next open rebuilds swap state from the
master. Crash-window durability unchanged (repair-on-open verifies every
sector, torn slots fall back to the master). See
`docs/architecture.md`.

## 2. Functional requirements

- FR1: read all five formats on every leg (Folia, Canvas 26.1.x/26.2,
  Horizon).
- FR2: write all five formats. Pumpkin writer defaults: grid 2,
  level-1-equivalent speed, `tmp + fsync + atomic move`.
- FR3: migration any-to-any (V1<->V2<->V3<->B_LINEAR<->PUMPKIN,
  including direct V1->V3 and V1->B_LINEAR) **only** via explicit
  `/linear convert --from X --to Y`. No silent cross-format rewrite.
- FR4: self-aware detection. Extension routes `.mca`/`.b_linear`;
  every `.linear` file is identified by try-parse with full structural
  validation per candidate (section 4). Payload bytes are never
  reinterpreted across layouts.
- FR5: envelope translation preserves chunk payload bytes exactly.
  Timestamp `u64 -> i32` clamps with a per-file log line; sizes
  `> 500 MB` reject; `len+8` (DivineMC) vs raw `len` (Pumpkin) handled
  per source format, never guessed.
- FR6: hash mismatch fails high on all five readers. No silent bucket
  skip.
- FR7: Pumpkin files keep the `.linear` extension (as upstream);
  converted outputs use the target tier's extension.
- FR8: Pumpkin preserve-on-flush. No `LinearImplementation` exists for Pumpkin, so the
  configured implementation cannot express it; silently rewriting Rust-generated files is
  the dangerous direction, so Pumpkin-loaded regions flush back as Pumpkin (round-trip fidelity).

## 3. Non-functional requirements

- NFR1 (perf): the V1 hot path stays allocation-identical —
  detect-once at open, then direct dispatch to the `Version`
  implementation. No adapter boxing on read/write.
- NFR2 (safety): bomb guards (`MAX_IMAGE`, 500 MB chunk, 1 GiB file),
  symlink guard, and footer checks apply to all five readers.
- NFR3 (durability): per-tier write discipline preserved
  (V1/V2/V3/Pumpkin `tmp + fsync + atomic move`; B_LINEAR swap+master).
- NFR4 (compat): existing worlds behave byte-identically unless an
  explicit convert runs. DivineMC-written V2/V3 keep reading;
  Pumpkin-written files become readable.
- NFR5 (fidelity): our Pumpkin output must be accepted by the real
  Pumpkin server (proven live); byte-identity with `ruzstd` output is
  explicitly NOT required.

## 4. Detection algorithm (normative)

For a `.linear` file, candidates are validated **fully** (not
header-sniffed — byte 17 alone cannot discriminate: V1 level 1..22
overlaps Pumpkin/V2 grid 1/2/4/8/16/32):

1. V1: magic + wire 1/2 + 32 B header sane + `chunkCount <= 1024` +
   `fileLength == 32 + dataCount + 8` + footer magic.
2. V2 (wire 3): magic + grid whitelist + features parse terminates +
   table bounds + bucket hash chain verifies.
3. V3 (wire 4): magic + grid whitelist + table bounds + bucket hash
   chain verifies.
4. PUMPKIN (wire 2, non-V1 body): magic + grid whitelist +
   `regionX/Z` **equal the `r.X.Z` filename coordinates** + features
   parse terminates + per-bucket xxhash verifies.

Exactly one candidate must validate. Zero -> corrupt-file error. Two
or more -> ambiguous-layout error naming both candidates. Neither case
may fall through into another format's reader.

## 5. Standardization rules (mandatory)

- R1: one file per version — `V1Linear`, `V2Linear`, `V3Linear`,
  `PumpkinLinear` — plus abstract `V2LinearBase` shared by the three
  bucketed readers. No version reaches into another version's
  internals; shared code lives in `*Base` or helpers only.
- R2: every version implements `Version`:
  `implementation()` / `version()` / `parse(ByteBuffer)` / `flush()`.
- R3: `LinearBase.getShared(byte)` is the single wire->implementation
  map (`1,2 -> V1`, `3 -> V2`, `4 -> V3`); Pumpkin resolves via the
  section-4 detector, never via the version byte.
- R4: `LinearImplementation` (`V1(2)/V2(3)/V3(4)`) stays the wire truth
  for config and headers.
- R5: bitmap endianness is per-format (V2 MSB-first, Pumpkin LSB-first)
  and must be covered by golden tests each.
- R6: every layout ships golden fixtures (DivineMC-written V2/V3
  already in-repo; Pumpkin fixtures generated by the real server) plus
  round-trip and cross-implementation tests.
- R7: divergences from upstream (timestamps preserved, fail-high hash,
  fsync on Pumpkin write) are documented here, not just in code.

## 6. Divergences from upstream

- Features capped at 64 entries / 4 KiB, no UTF-8 key validation; trailing bytes after footer rejected; `chunkCount>1024` hard-rejects at detect (warn-only in parse).
- V2/V3 sparse empty buckets (DivineMC-identical) vs Pumpkin dense; hash fail-high; timestamps preserved; fsync on write.
