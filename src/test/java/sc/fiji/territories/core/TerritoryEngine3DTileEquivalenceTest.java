package sc.fiji.territories.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

/**
 * The 0.2.2 tile assignment against the 0.2.1 engine kept as
 * {@link ReferenceTerritoryEngine3D}, which answers every voxel with the k-d
 * tree. Labels, cells and regularity must match bit for bit at one worker and
 * at eight, on filled boxes (tile path), boxes with holes and several
 * components, scattered sparse regions (k-d tree path), anisotropic and
 * non-dyadic calibration, and centroids on a coarse grid so distance ties
 * between objects are common.
 */
public class TerritoryEngine3DTileEquivalenceTest {

    @Test
    public void territoriesAreIdenticalToTheReference() {
        Random random = new Random(20260930L);
        int compared = 0;
        for (int trial = 0; trial < 90; trial++) {
            final RegionMask3D region = region(random, trial);
            final List<SpatialObject3D> objects = objects(random, region, trial);
            if (objects.isEmpty()) continue;
            for (final EdgeCellPolicy policy : EdgeCellPolicy.values()) {
                TerritoryResult3D expected = DensityParallelDeterminismTest.withParallelism(
                        "1", () -> ReferenceTerritoryEngine3D.analyze(objects, region, policy));
                for (String workers : new String[] {"1", "8"}) {
                    TerritoryResult3D actual = DensityParallelDeterminismTest.withParallelism(
                            workers, () -> TerritoryEngine3D.analyze(objects, region, policy));
                    String context = "trial " + trial + " " + policy + " x" + workers;
                    assertArrayEquals(context,
                            DensityParallelDeterminismTest.bits(expected.getTerritoryLabels()),
                            DensityParallelDeterminismTest.bits(actual.getTerritoryLabels()));
                    assertSameCells(context, expected.getCells(), actual.getCells());
                    assertSameRegularity(context, expected.getRegularity(), actual.getRegularity());
                    compared++;
                }
            }
        }
        assertTrue(compared > 300);
    }

    /**
     * Shapes by trial: filled box, box with holes, two to four separate
     * blocks, scattered voxels; union or one selected label.
     */
    private static RegionMask3D region(Random random, int trial) {
        int width = 4 + random.nextInt(trial < 20 ? 12 : 60);
        int height = 4 + random.nextInt(trial < 20 ? 12 : 50);
        int depth = 1 + random.nextInt(6);
        int[] labels = new int[width * height * depth];
        int shape = trial % 4;
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int value;
                    if (shape == 0) {
                        value = 1;
                    } else if (shape == 1) {
                        value = random.nextInt(8) == 0 ? 0 : 1;
                    } else if (shape == 2) {
                        // Vertical walls of background split the box into blocks.
                        value = x % 13 == 6 ? 0 : 1 + (x / 13) % 3;
                    } else {
                        value = random.nextInt(6) == 0 ? 1 : 0;
                    }
                    labels[(z * height + y) * width + x] = value;
                }
            }
        }
        boolean union = shape != 2 || random.nextBoolean();
        int selected = union ? 0 : 1;
        long voxels = 0;
        for (int value : labels) {
            if (union ? value > 0 : value == selected) voxels++;
        }
        double pw = trial % 3 == 0 ? 1.0 : 0.1 + random.nextInt(9) * 0.037;
        double ph = trial % 3 == 0 ? 1.0 : 0.1 + random.nextInt(9) * 0.041;
        double pd = trial % 3 == 0 ? 1.0 : 0.25 + random.nextInt(9) * 0.3;
        return new RegionMask3D("r" + trial, width, height, depth, pw, ph, pd, "micron",
                labels, selected, union, voxels);
    }

    /** Up to 120 objects inside the region, centroids on a quarter-voxel grid, all distinct. */
    private static List<SpatialObject3D> objects(Random random, RegionMask3D region, int trial) {
        List<SpatialObject3D> objects = new ArrayList<SpatialObject3D>();
        Set<String> seen = new HashSet<String>();
        int wanted = 1 + random.nextInt(trial < 20 ? 8 : 120);
        for (int attempt = 0; attempt < wanted * 20 && objects.size() < wanted; attempt++) {
            int x = random.nextInt(region.getWidth());
            int y = random.nextInt(region.getHeight());
            int z = random.nextInt(region.getDepth());
            if (!region.contains(x, y, z)) continue;
            double cx = (x + random.nextInt(4) * 0.25) * region.getPixelWidth();
            double cy = (y + random.nextInt(4) * 0.25) * region.getPixelHeight();
            double cz = (z + random.nextInt(4) * 0.25) * region.getPixelDepth();
            if (Math.floor(cx / region.getPixelWidth()) != x
                    || Math.floor(cy / region.getPixelHeight()) != y
                    || Math.floor(cz / region.getPixelDepth()) != z) {
                continue;
            }
            if (!seen.add(cx + "," + cy + "," + cz)) continue;
            int index = objects.size() * 2 + 1;
            objects.add(new SpatialObject3D(index, index % 3, "T" + (index % 3),
                    objects.size() + 1, cx, cy, cz, 1.0));
        }
        java.util.Collections.shuffle(objects, random);
        return objects;
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
