package sc.fiji.territories.core;

import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;

public class InteractionEngineParallelismTest {

    @Test
    public void parallelPermutationsPreserveLegacyShuffleStream() {
        List<SpatialObject2D> objects = Arrays.asList(
                object(0, 0, 0, 2.0, 2.0), object(1, 1, 1, 5.0, 2.0),
                object(2, 0, 2, 8.0, 2.0), object(3, 1, 3, 2.0, 8.0),
                object(4, 1, 4, 5.0, 8.0), object(5, 0, 5, 8.0, 8.0));
        GeometryFactory factory = new GeometryFactory();
        SpatialRegion2D region = new SpatialRegion2D("square", factory.createPolygon(
                new Coordinate[]{new Coordinate(0, 0), new Coordinate(10, 0),
                        new Coordinate(10, 10), new Coordinate(0, 10),
                        new Coordinate(0, 0)}));
        TerritoryResult territories = TerritoryEngine.analyze(
                objects, region, EdgeCellPolicy.INCLUDE_FLAGGED);

        String previous = System.getProperty("territories.parallelism");
        try {
            System.setProperty("territories.parallelism", "1");
            InteractionMatrixResult serial = InteractionEngine.analyze(
                    territories.getCells(), Arrays.asList("A", "B"), 251, 424242L);
            System.setProperty("territories.parallelism", "4");
            InteractionMatrixResult parallel = InteractionEngine.analyze(
                    territories.getCells(), Arrays.asList("A", "B"), 251, 424242L);
            assertMatrixEquals(serial.getCounts(), parallel.getCounts());
            assertMatrixEquals(serial.getExpectedCounts(), parallel.getExpectedCounts());
            assertMatrixEquals(serial.getZScores(), parallel.getZScores());
            assertMatrixEquals(serial.getTwoSidedPValues(), parallel.getTwoSidedPValues());
        } finally {
            restore("territories.parallelism", previous);
        }
    }

    private static SpatialObject2D object(
            int index, int type, long label, double x, double y) {
        return new SpatialObject2D(
                index, type, type == 0 ? "A" : "B", label + 1, x, y, 1.0);
    }

    private static void assertMatrixEquals(int[][] expected, int[][] actual) {
        for (int row = 0; row < expected.length; row++) {
            assertArrayEquals(expected[row], actual[row]);
        }
    }

    private static void assertMatrixEquals(double[][] expected, double[][] actual) {
        for (int row = 0; row < expected.length; row++) {
            assertArrayEquals(expected[row], actual[row], 0.0);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
