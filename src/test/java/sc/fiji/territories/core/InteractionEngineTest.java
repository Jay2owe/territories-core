package sc.fiji.territories.core;

import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InteractionEngineTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    public void checkerboardCountsEveryUndirectedBoundaryOnce() {
        List<SpatialObject2D> objects = Arrays.asList(
                object(0, 0, 0, 2.5, 2.5),
                object(1, 1, 0, 7.5, 2.5),
                object(2, 1, 1, 2.5, 7.5),
                object(3, 0, 1, 7.5, 7.5));
        TerritoryResult territories = TerritoryEngine.analyze(
                objects, square(), EdgeCellPolicy.INCLUDE_FLAGGED);

        InteractionMatrixResult result = InteractionEngine.analyze(
                territories.getCells(), Arrays.asList("A", "B"), 99, 42L);

        int[][] counts = result.getCounts();
        assertArrayEquals(new int[] {0, 4}, counts[0]);
        assertArrayEquals(new int[] {4, 0}, counts[1]);
        assertEquals(99, result.getPermutations());
        assertEquals(42L, result.getSeed());
        assertTrue(result.getTwoSidedPValues()[0][1] > 0.0);
        assertEquals(
                result.getTwoSidedPValues()[0][1],
                result.getTwoSidedPValues()[1][0],
                0.0);
    }

    @Test
    public void oneTypeHasExpectedPValueOfOne() {
        List<SpatialObject2D> objects = Arrays.asList(
                object(0, 0, 0, 2.5, 2.5),
                object(1, 0, 1, 7.5, 2.5),
                object(2, 0, 2, 2.5, 7.5),
                object(3, 0, 3, 7.5, 7.5));
        TerritoryResult territories = TerritoryEngine.analyze(
                objects, square(), EdgeCellPolicy.INCLUDE_FLAGGED);

        InteractionMatrixResult result = InteractionEngine.analyze(
                territories.getCells(), Arrays.asList("A"), 20, 7L);

        assertEquals(4, result.getCounts()[0][0]);
        assertEquals(4.0, result.getExpectedCounts()[0][0], 0.0);
        assertEquals(0.0, result.getZScores()[0][0], 0.0);
        assertEquals(1.0, result.getTwoSidedPValues()[0][0], 0.0);
    }

    /**
     * A single permutation gives no spread, so the null is undefined rather
     * than infinitely narrow — an unstandardisable z must not read as a
     * maximally significant result.
     */
    @Test
    public void singlePermutationReportsUndefinedZScores() {
        List<SpatialObject2D> objects = Arrays.asList(
                object(0, 0, 0, 2.5, 2.5),
                object(1, 1, 0, 7.5, 2.5),
                object(2, 1, 1, 2.5, 7.5),
                object(3, 0, 1, 7.5, 7.5));
        TerritoryResult territories = TerritoryEngine.analyze(
                objects, square(), EdgeCellPolicy.INCLUDE_FLAGGED);

        InteractionMatrixResult result = InteractionEngine.analyze(
                territories.getCells(), Arrays.asList("A", "B"), 1, 12345L);

        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                assertTrue(
                        "z-score at " + a + "," + b + " should be NaN",
                        Double.isNaN(result.getZScores()[a][b]));
            }
        }
    }

    /**
     * With one type every permutation reproduces the observed counts, so the
     * null has no spread at all. Across many permutations that is a genuine
     * "exactly as expected"; from a single draw it is simply unknown.
     */
    @Test
    public void singlePermutationIsUndefinedEvenWhenObservedMatchesExpected() {
        List<SpatialObject2D> objects = Arrays.asList(
                object(0, 0, 0, 2.5, 2.5),
                object(1, 0, 1, 7.5, 2.5),
                object(2, 0, 2, 2.5, 7.5),
                object(3, 0, 3, 7.5, 7.5));
        TerritoryResult territories = TerritoryEngine.analyze(
                objects, square(), EdgeCellPolicy.INCLUDE_FLAGGED);

        InteractionMatrixResult single = InteractionEngine.analyze(
                territories.getCells(), Arrays.asList("A"), 1, 7L);
        InteractionMatrixResult many = InteractionEngine.analyze(
                territories.getCells(), Arrays.asList("A"), 50, 7L);

        assertEquals(4, single.getCounts()[0][0]);
        assertEquals(4.0, single.getExpectedCounts()[0][0], 0.0);
        assertTrue(
                "a single permutation cannot standardise anything",
                Double.isNaN(single.getZScores()[0][0]));
        assertEquals(0.0, many.getZScores()[0][0], 0.0);
    }

    private static SpatialObject2D object(
            int index, int type, long label, double x, double y) {
        return new SpatialObject2D(index, type, type == 0 ? "A" : "B", label + 1, x, y, 1.0);
    }

    private static SpatialRegion2D square() {
        return new SpatialRegion2D("Square", GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
                new Coordinate(0.0, 0.0),
                new Coordinate(10.0, 0.0),
                new Coordinate(10.0, 10.0),
                new Coordinate(0.0, 10.0),
                new Coordinate(0.0, 0.0)
        }));
    }
}

