# territories-core

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21933304.svg)](https://doi.org/10.5281/zenodo.21933304)

The Object Territories engine, as an embeddable module.

**Status (2026-09-30): 0.2.2, released; Object Territories 0.3.1 ships 0.2.1.**
69 tests green here. 0.2.2 assigns 3D territories tile by tile instead of one
k-d tree search per voxel: 1.8x faster end to end in Object Colocalization
Suite's territory benchmark (3.5x on one processor), outputs bit-identical to
0.2.1, 0.2.0 and 0.1.0 (see `CHANGELOG.md`). The plugin's 1,221 golden cases
are unmoved against it, bit-for-bit.

**Pattern:** `../PLUGIN_CORE_PATTERN.md`
**Depends on:** `net.imagej:ij` (provided) and `org.locationtech.jts:jts-core`
1.19.0 (compile). **Not** `oc3d-core` — the engine calls nothing in the chassis.
**Never shipped as a jar.**

| Class | Role |
|---|---|
| `TerritoryEngine` | 2D — exact Voronoi tessellation clipped to an arbitrary region, Delaunay adjacency, edge-cell flags, regularity |
| `TerritoryEngine3D` | 3D — voxel-resolved territories by calibrated nearest centroid, 6-connected adjacency |
| `DensityEngine` / `DensityEngine3D` | Gaussian KDE, corrected or clipped at the region boundary, plus leave-one-out local density per object |
| `InteractionEngine` | permutation-tested neighbourhood enrichment matrix, seeded and order-stable under parallelism |
| `LabelObjectExtractor` / `LabelObjectExtractor3D` | calibrated centroids, areas and volumes from a label image |
| `RegionFactory` | ImageJ ROI → valid calibrated JTS region, clipped to the imaged field |
| `RegionMaskFactory3D` | positive-integer 3D mask → independent or unioned regions |
| `TerritoryResult` / `TerritoryResult3D` / `DensityResult` / `InteractionMatrixResult` | result models — **no ImageJ tables** |
| `EdgeCellPolicy` `RegionMode` `DensityWeighting` `DensityBoundaryMode` | the four options the engine itself reads |
| `ComputationCancelledException` | thrown when a caller's cancellation check fires part-way through |

Build and test:

```
mvn -o test        # 67 tests
mvn -o install     # needed before Object Territories can build
```

## What this is

Object Territories' analysis engine with the dialog, the entry classes, the
`ResultsTable` builders, the CSV/TIFF exporters and the batch runner stripped
out, so another plugin can compile it in and answer "which territory is this
object in" without the user installing Object Territories.

## Consuming it

```xml
<dependency>
  <groupId>io.github.jay2owe</groupId>
  <artifactId>territories-core</artifactId>
  <version>0.2.2</version>
</dependency>
```

```xml
<relocation>
  <pattern>sc.fiji.territories.core</pattern>
  <shadedPattern>ocs.internal.territories</shadedPattern>
</relocation>
<relocation>
  <pattern>org.locationtech.jts</pattern>
  <shadedPattern>ocs.internal.shaded.jts</shadedPattern>
</relocation>
```

**JTS must be relocated too.** It arrives transitively, it is on every 2D code
path, and two jars carrying `org.locationtech.jts.geom.Geometry` is a
silent-wrong-answer bug under Fiji's flat classloader, not a crash.

`net.imagej:ij` is `provided` and must never be bundled — Fiji already is
ImageJ.

## The territory-assignment surface

The question a consumer usually has is *which territory does this object fall
in*, and the two paths answer it differently.

**3D — a raster, directly usable.**
`TerritoryResult3D.getTerritoryLabels()` returns a caller-owned 32-bit stack
whose voxel value is `global object index + 1`, and `0` outside the region.
Look up the voxel under any point and you have its territory owner.

**2D — a polygon per cell.**
`TerritoryCell.getGeometry()` returns the clipped Voronoi polygon in
*calibrated* coordinates. A consumer locates a point by testing
`geometry.covers(point)`. There is no 2D raster equivalent; the plugin's map
renderer builds one for display only and lives on the plugin side.

The identifier of a territory is the **global object index** — the `int` from
`SpatialObject2D.getIndex()` / `SpatialObject3D.getIndex()`, assigned in the
order the caller supplies label images and, within an image, in ascending
label-first-seen order. It is not the label value and not a per-region index.
`getLabel()` is the original label-image value; `getTypeIndex()` is which of
the 1–5 input images it came from.

## No dialog, no Swing, no `IJ.error`

Must run headless. Throws `IllegalArgumentException`; the plugin presents.
`java.awt.geom` is used inside `RegionFactory` only to read an ImageJ ROI's
path — geometry, not a user interface.

## Parallel execution

Since 0.2.0 the density engines (`DensityEngine`, `DensityEngine3D`) and 3D
territory assignment (`TerritoryEngine3D`) spread their work across threads,
as `InteractionEngine` already did for permutations. Outputs are
bit-identical to the serial code at every thread count: each pixel or voxel
is written by one thread only, and it receives its kernel contributions in
the original object order, so every float addition happens in the same
sequence. `DensityParallelDeterminismTest` and
`TerritoryEngine3DParallelTest` check this bit for bit.

One rule sets the thread count for the whole module: the
`territories.parallelism` system property when it is positive, otherwise the
available processors capped at 8. `-Dterritories.parallelism=1` runs every
engine serially on the calling thread.

## Cancellation

Since 0.2.1, `DensityEngine.generate`, `DensityEngine3D.generate`,
`TerritoryEngine3D.analyze` and `InteractionEngine.analyze` each have an
overload taking a `java.util.function.BooleanSupplier` as the last argument.
The engine polls it per image row, slice, kernel, object or permutation
(every 4,096 voxels in the flat 3D passes), from its worker
threads as well as the calling thread, and throws
`ComputationCancelledException` as soon as it returns `true`. The supplier
must therefore be thread-safe and cheap, such as a volatile read. A poll only
reads, so a check that never fires gives output bit-identical to the
overloads without one; `null` never cancels. `CancellationTest` checks both
halves of that contract at 1 and 8 workers.

```java
AtomicBoolean stop = new AtomicBoolean();   // set from another thread to cancel
DensityResult map = DensityEngine.generate(objects, region, "A", width, height,
        pixelWidth, pixelHeight, "um", 40.0, DensityWeighting.OBJECT_COUNT,
        DensityBoundaryMode.CORRECTED, stop::get);
```

Object Territories 0.3.1 wires this to Escape; in its tests a density map is
stopped within a few milliseconds of the request.

## Citation

> Malcolm, J. (2026). *territories-core: Embeddable spatial territory and
> density engine* (Version 0.1.0) [Computer software]. Zenodo.
> https://doi.org/10.5281/zenodo.21933305

## Licence

BSD-3-Clause — see `LICENSE`. Attribution and third-party notices are in
`NOTICE`.

JTS supplies the 2D path: exact Voronoi tessellation, Delaunay adjacency and
polygon overlay. It is dual-licensed **EPL 2.0 / EDL 1.0**. EDL 1.0 is BSD-3
in substance, so a BSD-3 consumer takes JTS under EDL and ships under BSD-3
with the JTS notice retained. Nothing here links GPL.

This module carries that notice itself, at
`src/main/resources/META-INF/licenses/JTS-LICENSE.txt`, so it travels with any
jar that bundles or shades the module — a consumer inherits it rather than
having to remember it. `04 - Object Territories` carries the same file at the
same path.

## Ship gate

`../oc3d-core/EQUIVALENCE_HARNESS.md`, applied at
`04 - Object Territories/src/test/java/territories/equivalence/`. Everything is
**Tier 1 — bit-identical, no tolerance**, floating-point territory areas
included; see `DECISIONS.md` § 4 for why no Tier 2 band was declared.

Decisions taken during extraction: `DECISIONS.md`.
