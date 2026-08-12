package sc.fiji.territories.core;

import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Pins the permutation merge itself, not merely the statistics built on it.
 *
 * <p>{@link InteractionEngineParallelismTest} compares a serial run against a
 * parallel one, which sounds like it covers this and does not. Every statistic
 * {@link InteractionMatrixResult} exposes is a <em>symmetric</em> function of
 * the per-permutation count matrices: the expected count is their mean, the
 * two-sided p-value counts how many are at least as extreme, and neither cares
 * which permutation index held which matrix. Storing results by completion
 * order instead of by permutation index therefore leaves all of them
 * arithmetically unchanged.
 *
 * <p>Measured, not assumed. Mutating {@code InteractionEngine.nullCounts} to
 * store by completion order left the serial/parallel comparison <b>passing on
 * four runs out of five</b>: it failed only when the two orderings happened to
 * move the last bit of one z-score, because summing the same multiset of
 * squared deviations in a different order is not associative. That is an
 * accident, not a gate.
 *
 * <p>What the mutation genuinely breaks is reproducibility — thread completion
 * order varies between runs, so the last bits of the z-scores wander. This
 * test pins that directly by running the parallel path repeatedly and
 * requiring every run to agree bit for bit. Against the same mutation it fails
 * five times out of five.
 */
public class InteractionMergeDeterminismTest {

    /** Enough repeats that a wandering last bit cannot hide behind luck. */
    private static final int REPEATS = 24;

    private static final int PERMUTATIONS = 401;

    private static final long SEED = 7L;

    @Test
    public void repeatedParallelRunsAgreeBitForBit() {
        List<SpatialObject2D> objects = new ArrayList<SpatialObject2D>();
        for (int i = 0; i < 12; i++) {
            objects.add(new SpatialObject2D(
                    i,
                    i % 2,
                    i % 2 == 0 ? "A" : "B",
                    i + 1,
                    1.0 + (i % 4) * 2.7,
                    1.0 + (i / 4) * 3.1,
                    1.0));
        }
        GeometryFactory factory = new GeometryFactory();
        SpatialRegion2D region = new SpatialRegion2D("square", factory.createPolygon(
                new Coordinate[] {
                        new Coordinate(0, 0), new Coordinate(12, 0),
                        new Coordinate(12, 12), new Coordinate(0, 12),
                        new Coordinate(0, 0)}));
        TerritoryResult territories = TerritoryEngine.analyze(
                objects, region, EdgeCellPolicy.INCLUDE_FLAGGED);
        List<String> types = Arrays.asList("A", "B");

        String previous = System.getProperty("territories.parallelism");
        try {
            System.setProperty("territories.parallelism", "8");
            String reference = render(InteractionEngine.analyze(
                    territories.getCells(), types, PERMUTATIONS, SEED));
            for (int repeat = 1; repeat <= REPEATS; repeat++) {
                assertEquals(
                        "parallel run " + repeat + " of " + REPEATS
                                + " disagreed with the first; the permutation merge is"
                                + " storing results by completion order, not by index",
                        reference,
                        render(InteractionEngine.analyze(
                                territories.getCells(), types, PERMUTATIONS, SEED)));
            }
        } finally {
            restore(previous);
        }
    }

    /**
     * Renders the order-sensitive statistics as raw IEEE-754 bit patterns.
     * A delta comparison would step over exactly the last-bit drift this test
     * exists to catch, and the observed counts are excluded deliberately —
     * they never touch the permutation stream, so including them would only
     * dilute the signal.
     */
    private static String render(InteractionMatrixResult result) {
        StringBuilder text = new StringBuilder();
        append(text, result.getExpectedCounts());
        append(text, result.getZScores());
        append(text, result.getTwoSidedPValues());
        return text.toString();
    }

    private static void append(StringBuilder text, double[][] values) {
        for (int row = 0; row < values.length; row++) {
            for (int column = 0; column < values[row].length; column++) {
                text.append(Long.toHexString(
                        Double.doubleToRawLongBits(values[row][column]))).append(' ');
            }
        }
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty("territories.parallelism");
        else System.setProperty("territories.parallelism", previous);
    }
}
