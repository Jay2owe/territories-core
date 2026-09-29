package sc.fiji.territories.core;

import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static sc.fiji.territories.core.DensityParallelDeterminismTest.bits;
import static sc.fiji.territories.core.DensityParallelDeterminismTest.objects3D;
import static sc.fiji.territories.core.DensityParallelDeterminismTest.slabMask;
import static sc.fiji.territories.core.DensityParallelDeterminismTest.withParallelism;

/**
 * The cancellation overloads: a check that never fires changes nothing, bit
 * for bit; a check that fires part-way stops the computation with
 * {@link ComputationCancelledException}, serially and in parallel.
 */
public class CancellationTest {

    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final String[] PARALLELISM = {"1", "8"};

    @Test
    public void twoDimensionalDensity() {
        final SpatialRegion2D region = new SpatialRegion2D("Field", GEOMETRY.createPolygon(
                new Coordinate[]{new Coordinate(1, 1), new Coordinate(60, 1),
                        new Coordinate(60, 50), new Coordinate(1, 50), new Coordinate(1, 1)}));
        final List<SpatialObject2D> objects = new ArrayList<SpatialObject2D>();
        Random random = new Random(5L);
        for (int i = 0; i < 120; i++) {
            objects.add(new SpatialObject2D(i, 0, "A", i + 1L,
                    2 + random.nextDouble() * 57, 2 + random.nextDouble() * 47,
                    1 + random.nextDouble() * 9));
        }
        for (final DensityBoundaryMode mode : DensityBoundaryMode.values()) {
            Function<BooleanSupplier, int[]> run = check -> {
                DensityResult result = check == null
                        ? DensityEngine.generate(objects, region, "A", 64, 53, 1.0, 1.0,
                                "um", 4.0, DensityWeighting.OBJECT_SIZE, mode)
                        : DensityEngine.generate(objects, region, "A", 64, 53, 1.0, 1.0,
                                "um", 4.0, DensityWeighting.OBJECT_SIZE, mode, check);
                return concat(bits(result.getDensityMap()),
                        densityBits(result.getLocalDensityByObjectIndex()));
            };
            assertCancellationContract("2D density " + mode, run);
        }
    }

    @Test
    public void threeDimensionalDensity() {
        final RegionMask3D region = slabMask();
        final List<SpatialObject3D> objects = objects3D(region, 90, 23L);
        for (final DensityBoundaryMode mode : DensityBoundaryMode.values()) {
            Function<BooleanSupplier, int[]> run = check -> {
                DensityResult3D result = check == null
                        ? DensityEngine3D.generate(objects, region, "A", 1.5,
                                DensityWeighting.OBJECT_COUNT, mode)
                        : DensityEngine3D.generate(objects, region, "A", 1.5,
                                DensityWeighting.OBJECT_COUNT, mode, check);
                return concat(bits(result.getDensityVolume()),
                        densityBits(result.getLocalDensityByObjectIndex()));
            };
            assertCancellationContract("3D density " + mode, run);
        }
    }

    @Test
    public void threeDimensionalTerritories() {
        final RegionMask3D region = slabMask();
        final List<SpatialObject3D> objects = objects3D(region, 90, 23L);
        Function<BooleanSupplier, int[]> run = check -> {
            TerritoryResult3D result = check == null
                    ? TerritoryEngine3D.analyze(objects, region, EdgeCellPolicy.INCLUDE_FLAGGED)
                    : TerritoryEngine3D.analyze(
                            objects, region, EdgeCellPolicy.INCLUDE_FLAGGED, check);
            return concat(bits(result.getTerritoryLabels()), cellSignature(result));
        };
        assertCancellationContract("3D territories", run);
    }

    @Test
    public void interactionPermutations() {
        final RegionMask3D region = slabMask();
        final List<TerritoryCell3D> cells = TerritoryEngine3D.analyze(
                objects3D(region, 90, 23L), region, EdgeCellPolicy.INCLUDE_FLAGGED).getCells();
        final List<String> types = Arrays.asList("A", "B");
        Function<BooleanSupplier, int[]> run = check -> {
            InteractionMatrixResult result = check == null
                    ? InteractionEngine.analyze(cells, types, 199, 42L)
                    : InteractionEngine.analyze(cells, types, 199, 42L, check);
            return concat(
                    flatten(result.getCounts()),
                    doubleBits(result.getExpectedCounts()),
                    doubleBits(result.getZScores()),
                    doubleBits(result.getTwoSidedPValues()));
        };
        assertCancellationContract("interactions", run);
    }

    /**
     * For every worker count: a counting check that never fires gives the
     * same bits as the overload without one (and as a null check) and is
     * actually polled; a check that fires at the first poll, and one that
     * fires half-way through the polls, both stop the run.
     */
    private static void assertCancellationContract(
            final String what, final Function<BooleanSupplier, int[]> run) {
        for (String workers : PARALLELISM) {
            final String context = what + " x" + workers;
            withParallelism(workers, () -> {
                int[] plain = run.apply(null);
                final AtomicInteger polls = new AtomicInteger();
                int[] checked = run.apply(() -> {
                    polls.incrementAndGet();
                    return false;
                });
                assertArrayEquals(context + ": never-firing check changed the output",
                        plain, checked);
                assertTrue(context + ": the check was never polled", polls.get() > 1);

                expectCancelled(context + " (at once)", run, () -> true);
                final AtomicInteger countdown = new AtomicInteger(polls.get() / 2);
                expectCancelled(context + " (half-way)", run,
                        () -> countdown.decrementAndGet() < 0);
                return null;
            });
        }
    }

    private static void expectCancelled(
            String context, Function<BooleanSupplier, int[]> run, BooleanSupplier check) {
        try {
            run.apply(check);
        } catch (ComputationCancelledException expected) {
            return;
        }
        fail(context + ": run finished despite the check firing");
    }

    // -- signatures -----------------------------------------------------------

    private static int[] cellSignature(TerritoryResult3D result) {
        ArrayList<Integer> values = new ArrayList<Integer>();
        for (TerritoryCell3D cell : result.getCells()) {
            values.add(cell.getObject().getIndex());
            values.add((int) cell.getVoxelCount());
            values.add(cell.isEdgeCell() ? 1 : 0);
            values.addAll(cell.getNeighborObjectIndices());
            values.add(-1);
        }
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    private static int[] densityBits(Map<Integer, Double> densities) {
        int[] out = new int[densities.size() * 3];
        int i = 0;
        for (Map.Entry<Integer, Double> entry : densities.entrySet()) {
            long raw = Double.doubleToRawLongBits(entry.getValue());
            out[i++] = entry.getKey();
            out[i++] = (int) raw;
            out[i++] = (int) (raw >>> 32);
        }
        return out;
    }

    private static int[] doubleBits(double[][] matrix) {
        ArrayList<Integer> values = new ArrayList<Integer>();
        for (double[] row : matrix) {
            for (double value : row) {
                long raw = Double.doubleToRawLongBits(value);
                values.add((int) raw);
                values.add((int) (raw >>> 32));
            }
        }
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    private static int[] flatten(int[][] matrix) {
        int[] out = new int[0];
        for (int[] row : matrix) out = concat(out, row);
        return out;
    }

    private static int[] concat(int[]... parts) {
        int length = 0;
        for (int[] part : parts) length += part.length;
        int[] out = new int[length];
        int at = 0;
        for (int[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
