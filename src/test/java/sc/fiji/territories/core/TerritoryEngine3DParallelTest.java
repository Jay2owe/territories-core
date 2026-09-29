package sc.fiji.territories.core;

import org.junit.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * 3D territory assignment runs voxels on several workers, each with its own
 * nearest-centroid query; labels, cells and regularity must not depend on
 * the worker count.
 */
public class TerritoryEngine3DParallelTest {

    @Test
    public void territoriesAreIdenticalAtAnyWorkerCount() {
        final RegionMask3D region = DensityParallelDeterminismTest.slabMask();
        for (long seed : new long[]{5L, 6L, 7L}) {
            final List<SpatialObject3D> objects =
                    DensityParallelDeterminismTest.objects3D(region, 60, seed);
            for (final EdgeCellPolicy policy : EdgeCellPolicy.values()) {
                Supplier<TerritoryResult3D> run =
                        () -> TerritoryEngine3D.analyze(objects, region, policy);
                TerritoryResult3D serial =
                        DensityParallelDeterminismTest.withParallelism("1", run);
                for (String workers : new String[]{"3", "8"}) {
                    TerritoryResult3D parallel =
                            DensityParallelDeterminismTest.withParallelism(workers, run);
                    String context = "seed " + seed + " " + policy + " x" + workers;
                    assertArrayEquals(context,
                            DensityParallelDeterminismTest.bits(serial.getTerritoryLabels()),
                            DensityParallelDeterminismTest.bits(parallel.getTerritoryLabels()));
                    assertSameCells(context, serial.getCells(), parallel.getCells());
                    assertSameRegularity(
                            context, serial.getRegularity(), parallel.getRegularity());
                }
            }
        }
    }

    @Test
    public void emptyComponentStaysUnassigned() {
        RegionMask3D region = DensityParallelDeterminismTest.slabMask();
        List<SpatialObject3D> objects = DensityParallelDeterminismTest.objects3D(region, 60, 5L);
        TerritoryResult3D result = DensityParallelDeterminismTest.withParallelism(
                "8", () -> TerritoryEngine3D.analyze(
                        objects, region, EdgeCellPolicy.INCLUDE_FLAGGED));
        // Column 21 belongs to the last slab, which holds no object.
        assertEquals(0.0f, result.getTerritoryLabels().getStack().getProcessor(1).getf(21, 0), 0.0f);
        long assigned = 0;
        for (TerritoryCell3D cell : result.getCells()) assigned += cell.getVoxelCount();
        // Three occupied slabs of 7, 6 and 5 columns, 19 rows, 13 slices.
        assertEquals((7 + 6 + 5) * 19L * 13L, assigned);
    }

    private static void assertSameCells(
            String context, List<TerritoryCell3D> expected, List<TerritoryCell3D> actual) {
        assertEquals(context, expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            TerritoryCell3D want = expected.get(i);
            TerritoryCell3D got = actual.get(i);
            assertEquals(context, want.getObject().getIndex(), got.getObject().getIndex());
            assertEquals(context, want.getVoxelCount(), got.getVoxelCount());
            assertEquals(context, Double.doubleToRawLongBits(want.getVolume()),
                    Double.doubleToRawLongBits(got.getVolume()));
            assertEquals(context, want.getNeighborObjectIndices(), got.getNeighborObjectIndices());
            assertEquals(context, want.isEdgeCell(), got.isEdgeCell());
        }
    }

    private static void assertSameRegularity(
            String context, RegularityResult expected, RegularityResult actual) {
        assertEquals(context, expected.getIncludedObjects(), actual.getIncludedObjects());
        assertBits(context, expected.getTerritorySizeCoefficientOfVariation(),
                actual.getTerritorySizeCoefficientOfVariation());
        assertBits(context, expected.getNearestNeighborMean(), actual.getNearestNeighborMean());
        assertBits(context, expected.getNearestNeighborStandardDeviation(),
                actual.getNearestNeighborStandardDeviation());
        assertBits(context, expected.getNearestNeighborRegularityRatio(),
                actual.getNearestNeighborRegularityRatio());
    }

    private static void assertBits(String context, double expected, double actual) {
        assertEquals(context, Double.doubleToRawLongBits(expected),
                Double.doubleToRawLongBits(actual));
    }
}
