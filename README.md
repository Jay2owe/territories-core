# territories-core

The Object Territories engine, as an embeddable module.

**Status (2026-08-11): built, adopted and shipping inside the plugin.**
52 tests green here; Object Territories runs on it, its own copy of the engine
deleted, 37 tests green and **1,221 golden cases unmoved, bit-for-bit.**

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

Build and test:

```
mvn -o test        # 52 tests
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
  <version>0.1.0</version>
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
