package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Density maps and local densities must be bit-identical at any worker count:
 * parallelism 1 (the serial path) against 3 and 8, on randomised objects in
 * regions with several disconnected components, both boundary modes, both
 * weightings, fixed and automatic bandwidths, and image heights that no
 * worker count divides evenly.
 */
public class DensityParallelDeterminismTest {

    private static final GeometryFactory GEOMETRY = new GeometryFactory();
    private static final String[] PARALLELISM = {"3", "8"};

    @Test
    public void twoDimensionalDensityIsBitIdenticalAtAnyWorkerCount() {
        final int width = 97;
        final int height = 83;
        final double pixelWidth = 0.7;
        final double pixelHeight = 0.9;
        final SpatialRegion2D region = new SpatialRegion2D("Pieces", pieces());
        final List<SpatialObject2D> objects = objects2D(region.getGeometry(), 140, 11L);
        int compared = 0;
        for (final double bandwidth : new double[]{3.0, 0.0}) {
            for (final DensityWeighting weighting : DensityWeighting.values()) {
                for (final DensityBoundaryMode mode : DensityBoundaryMode.values()) {
                    Supplier<DensityResult> run = () -> DensityEngine.generate(
                            objects, region, "A", width, height, pixelWidth, pixelHeight,
                            "um", bandwidth, weighting, mode);
                    DensityResult serial = withParallelism("1", run);
                    for (String workers : PARALLELISM) {
                        DensityResult parallel = withParallelism(workers, run);
                        String context = bandwidth + " " + weighting + " " + mode + " x" + workers;
                        assertEquals(context, serial.getBandwidthMicrons(),
                                parallel.getBandwidthMicrons(), 0.0);
                        assertArrayEquals(context, bits(serial.getDensityMap()),
                                bits(parallel.getDensityMap()));
                        assertSameDensities(context, serial.getLocalDensityByObjectIndex(),
                                parallel.getLocalDensityByObjectIndex());
                        compared++;
                    }
                }
            }
        }
        assertEquals(expectedComparisons(), compared);
    }

    @Test
    public void threeDimensionalDensityIsBitIdenticalAtAnyWorkerCount() {
        final RegionMask3D region = slabMask();
        final List<SpatialObject3D> objects = objects3D(region, 90, 23L);
        int compared = 0;
        for (final double bandwidth : new double[]{1.5, 0.0}) {
            for (final DensityWeighting weighting : DensityWeighting.values()) {
                for (final DensityBoundaryMode mode : DensityBoundaryMode.values()) {
                    Supplier<DensityResult3D> run = () -> DensityEngine3D.generate(
                            objects, region, "A", bandwidth, weighting, mode);
                    DensityResult3D serial = withParallelism("1", run);
                    for (String workers : PARALLELISM) {
                        DensityResult3D parallel = withParallelism(workers, run);
                        String context = bandwidth + " " + weighting + " " + mode + " x" + workers;
                        assertEquals(context, serial.getBandwidth(), parallel.getBandwidth(), 0.0);
                        assertArrayEquals(context, bits(serial.getDensityVolume()),
                                bits(parallel.getDensityVolume()));
                        assertSameDensities(context, serial.getLocalDensityByObjectIndex(),
                                parallel.getLocalDensityByObjectIndex());
                        compared++;
                    }
                }
            }
        }
        assertEquals(expectedComparisons(), compared);
    }

    /** Two bandwidths x every weighting x every boundary mode x each parallel worker count. */
    private static int expectedComparisons() {
        return 2 * DensityWeighting.values().length
                * DensityBoundaryMode.values().length * PARALLELISM.length;
    }

    @Test
    public void twoDimensionalUnsupportedKernelIsReportedInObjectOrder() {
        // Kernels 1 and 2 have no sampled support at this bandwidth; the
        // serial loop stops at object 1, and so must every worker count.
        final List<SpatialObject2D> objects = Arrays.asList(
                new SpatialObject2D(0, 0, "A", 1L, 2.5, 2.5, 1.0),
                new SpatialObject2D(1, 0, "A", 2L, 4.3, 4.3, 1.0),
                new SpatialObject2D(2, 0, "A", 3L, 6.7, 6.7, 1.0));
        final SpatialRegion2D region = new SpatialRegion2D("Field", rectangle(0, 0, 10, 10));
        Supplier<String> run = () -> {
            try {
                DensityEngine.generate(objects, region, "A", 10, 10, 1.0, 1.0, "um",
                        1.0e-12, DensityWeighting.OBJECT_COUNT, DensityBoundaryMode.CLIPPED);
            } catch (IllegalArgumentException expected) {
                return expected.getMessage();
            }
            return null;
        };
        String serial = withParallelism("1", run);
        assertTrue(serial, serial.startsWith("density kernel for A:2 "));
        for (String workers : PARALLELISM) assertEquals(serial, withParallelism(workers, run));
    }

    @Test
    public void threeDimensionalUnsupportedKernelIsReportedInObjectOrder() {
        final RegionMask3D region = slabMask();
        final List<SpatialObject3D> objects = Arrays.asList(
                new SpatialObject3D(0, 0, "A", 1L, 0.3, 0.4, 0.85, 1.0),
                new SpatialObject3D(1, 0, "A", 2L, 1.0, 1.0, 1.0, 1.0),
                new SpatialObject3D(2, 0, "A", 3L, 2.0, 2.0, 2.0, 1.0));
        Supplier<String> run = () -> {
            try {
                DensityEngine3D.generate(objects, region, "A", 1.0e-12,
                        DensityWeighting.OBJECT_COUNT, DensityBoundaryMode.CLIPPED);
            } catch (IllegalArgumentException expected) {
                return expected.getMessage();
            }
            return null;
        };
        String serial = withParallelism("1", run);
        assertTrue(serial, serial.startsWith("3D density kernel for A:2 "));
        for (String workers : PARALLELISM) assertEquals(serial, withParallelism(workers, run));
    }

    // -- inputs ---------------------------------------------------------------

    /** Three disconnected polygons: two rectangles and a triangle. */
    private static Geometry pieces() {
        Geometry triangle = GEOMETRY.createPolygon(new Coordinate[]{
                new Coordinate(5, 45), new Coordinate(30, 72),
                new Coordinate(5, 72), new Coordinate(5, 45)});
        return rectangle(2, 3, 30, 40).union(rectangle(35, 5, 65, 70)).union(triangle);
    }

    private static Geometry rectangle(double x0, double y0, double x1, double y1) {
        return GEOMETRY.createPolygon(new Coordinate[]{
                new Coordinate(x0, y0), new Coordinate(x1, y0), new Coordinate(x1, y1),
                new Coordinate(x0, y1), new Coordinate(x0, y0)});
    }

    /** Objects of types A and B at random points covered by {@code domain}. */
    private static List<SpatialObject2D> objects2D(Geometry domain, int count, long seed) {
        Random random = new Random(seed);
        ArrayList<SpatialObject2D> objects = new ArrayList<SpatialObject2D>();
        while (objects.size() < count) {
            double x = random.nextDouble() * 68.0;
            double y = random.nextDouble() * 75.0;
            Point point = GEOMETRY.createPoint(new Coordinate(x, y));
            if (!domain.covers(point)) continue;
            int index = objects.size();
            int type = index % 4 == 3 ? 1 : 0;
            objects.add(new SpatialObject2D(index, type, type == 0 ? "A" : "B",
                    index + 1L, x, y, 0.5 + random.nextDouble() * 20.0));
        }
        return objects;
    }

    /** Objects of types A and B at random points inside the region mask. */
    static List<SpatialObject3D> objects3D(RegionMask3D region, int count, long seed) {
        Random random = new Random(seed);
        ArrayList<SpatialObject3D> objects = new ArrayList<SpatialObject3D>();
        double spanX = region.getWidth() * region.getPixelWidth();
        double spanY = region.getHeight() * region.getPixelHeight();
        double spanZ = region.getDepth() * region.getPixelDepth();
        while (objects.size() < count) {
            double x = random.nextDouble() * spanX;
            double y = random.nextDouble() * spanY;
            double z = random.nextDouble() * spanZ;
            int voxelX = (int) Math.floor(x / region.getPixelWidth());
            // Leave the last slab empty so one component has no objects.
            if (voxelX >= 21) continue;
            if (!region.contains(voxelX,
                    (int) Math.floor(y / region.getPixelHeight()),
                    (int) Math.floor(z / region.getPixelDepth()))) {
                continue;
            }
            int index = objects.size();
            int type = index % 3 == 2 ? 1 : 0;
            objects.add(new SpatialObject3D(index, type, type == 0 ? "A" : "B",
                    index + 1L, x, y, z, 0.5 + random.nextDouble() * 30.0));
        }
        return objects;
    }

    /**
     * 23 x 19 x 13 mask, anisotropic voxels, four disconnected slabs along x
     * (columns 7, 14 and 20 are outside the region).
     */
    static RegionMask3D slabMask() {
        int width = 23;
        int height = 19;
        int depth = 13;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (x != 7 && x != 14 && x != 20) processor.set(x, y, 1);
                }
            }
            stack.addSlice(processor);
        }
        ImagePlus image = new ImagePlus("Slabs", stack);
        Calibration calibration = image.getCalibration();
        calibration.pixelWidth = 0.6;
        calibration.pixelHeight = 0.8;
        calibration.pixelDepth = 1.7;
        calibration.setUnit("um");
        return RegionMaskFactory3D.create(image, RegionMode.INDEPENDENT).get(0);
    }

    // -- comparison -----------------------------------------------------------

    static <T> T withParallelism(String workers, Supplier<T> body) {
        String previous = System.getProperty("territories.parallelism");
        try {
            System.setProperty("territories.parallelism", workers);
            return body.get();
        } finally {
            if (previous == null) System.clearProperty("territories.parallelism");
            else System.setProperty("territories.parallelism", previous);
        }
    }

    static int[] bits(ImagePlus image) {
        ImageStack stack = image.getStack();
        int plane = image.getWidth() * image.getHeight();
        int[] result = new int[plane * stack.getSize()];
        for (int z = 0; z < stack.getSize(); z++) {
            float[] pixels = (float[]) stack.getPixels(z + 1);
            for (int i = 0; i < plane; i++) {
                result[z * plane + i] = Float.floatToRawIntBits(pixels[i]);
            }
        }
        return result;
    }

    private static void assertSameDensities(
            String context, Map<Integer, Double> expected, Map<Integer, Double> actual) {
        assertEquals(context, new ArrayList<Integer>(expected.keySet()),
                new ArrayList<Integer>(actual.keySet()));
        for (Map.Entry<Integer, Double> entry : expected.entrySet()) {
            long want = Double.doubleToRawLongBits(entry.getValue());
            long got = Double.doubleToRawLongBits(actual.get(entry.getKey()));
            if (want != got) {
                fail(context + ": local density of object " + entry.getKey() + " differs");
            }
        }
    }
}
