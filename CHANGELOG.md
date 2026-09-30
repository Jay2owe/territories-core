# Changelog

## [0.2.2] - 2026-09-30

Faster 3D territories, with every output unchanged bit for bit. No public
class, method or result changed; a plugin built against 0.2.1 compiles and
runs unchanged.

### Performance

- **3D territories assigned tile by tile.** `TerritoryEngine3D` searched a
  k-d tree for the nearest object from every voxel of the region. The region is
  now cut into 16 x 16 x 1 tiles. For each tile, the smallest over all objects
  of the farthest distance to the tile's box bounds the answer from above, and
  only objects whose nearest distance to the box is within that bound (plus a
  margin for rounding) can own any voxel in it. Each voxel then compares just
  those candidates with the same squared-distance formula and the same
  lowest-index tie rule, so no voxel can change owner. Sparse or oddly shaped
  regions, where tiles would not pay, still use the k-d tree.
- **Cheaper boundary and adjacency checks.** A cell already flagged as touching
  the region edge skips the edge test, and neighbour links compare owners
  before asking whether the neighbour lies in the region.
- **Label extraction and region masks without per-voxel map lookups.**
  `LabelObjectExtractor3D` and `RegionMaskFactory3D` keep the last label at
  hand and read 8- and 16-bit in-memory stacks from their pixel arrays. Objects
  keep their first-seen order and index.

Measured through Object Colocalization Suite's release benchmark, case D
(territory occupancy in 3D; median of 3, 16 logical processors on a machine
already fully loaded by other work):

| Setting | 0.2.1 | 0.2.2 | Factor |
|---|---|---|---|
| default parallelism | 2.23 s | 1.23 s | 1.8x |
| one processor | 5.5 s | 1.6 s | 3.5x |

CPU time at default parallelism falls from 5.1 s to 1.9 s.

**Evidence that nothing moved:** the 0.2.1 classes are kept in the test
sources as `ReferenceTerritoryEngine3D`, `ReferenceLabelObjectExtractor3D` and
`ReferenceRegionMaskFactory3D`. `TerritoryEngine3DTileEquivalenceTest`
compares the territory label map, every cell's voxel count, volume, neighbours
and edge flag, and the regularity summary over 90 random scenes with one and
eight workers, and `ExtractorAndMaskEquivalenceTest` compares
extraction and masks over 200 random stacks, every value as raw bits. Object
Territories' 94 tests, including its golden cases, pass unchanged against this
build, and the Object Colocalization Suite benchmark's full output has the
same SHA-256 as on 0.2.1.

## [0.2.1] - 2026-09-29

A cancellation check the long loops poll, so a caller can stop a density map
or a 3D territory assignment part-way through. Outputs bit-identical to 0.2.0.

## [0.2.0] - 2026-09-29

Parallel density maps and 3D territory assignment, bit-identical to 0.1.0.

## [0.1.0] - 2026-08-14

First release: the Object Territories engine extracted verbatim.
