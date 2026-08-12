package sc.fiji.territories.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Observed neighbourhood counts and permutation-tested enrichment statistics. */
public final class InteractionMatrixResult {

    private final List<String> types;
    private final int[][] counts;
    private final double[][] expectedCounts;
    private final double[][] zScores;
    private final double[][] twoSidedPValues;
    private final int permutations;
    private final long seed;

    InteractionMatrixResult(
            List<String> types,
            int[][] counts,
            double[][] expectedCounts,
            double[][] zScores,
            double[][] twoSidedPValues,
            int permutations,
            long seed) {
        this.types = Collections.unmodifiableList(new ArrayList<String>(types));
        this.counts = copy(counts);
        this.expectedCounts = copy(expectedCounts);
        this.zScores = copy(zScores);
        this.twoSidedPValues = copy(twoSidedPValues);
        this.permutations = permutations;
        this.seed = seed;
    }

    public List<String> getTypes() {
        return types;
    }

    public int[][] getCounts() {
        return copy(counts);
    }

    public double[][] getExpectedCounts() {
        return copy(expectedCounts);
    }

    public double[][] getZScores() {
        return copy(zScores);
    }

    public double[][] getTwoSidedPValues() {
        return copy(twoSidedPValues);
    }

    public int getPermutations() {
        return permutations;
    }

    public long getSeed() {
        return seed;
    }

    private static int[][] copy(int[][] source) {
        int[][] result = new int[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }

    private static double[][] copy(double[][] source) {
        double[][] result = new double[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }
}

