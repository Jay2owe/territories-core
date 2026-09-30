package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * The 0.2.1 {@code TerritoryEngine3D}, kept verbatim (renamed) as the
 * reference {@link TerritoryEngine3DTileEquivalenceTest} compares against: it
 * answers every voxel with the k-d tree. Not used by the engine.
 *
 * <p>Voxel-resolved 3D Voronoi territories using calibrated Euclidean distance.
 *
 * <p>Territories share a neighbourhood edge only when voxels meet across a
 * face (6-connectivity). Results therefore remain resolution-dependent but are
 * genuinely volumetric and never projection-based.
 */
final class ReferenceTerritoryEngine3D {

    private ReferenceTerritoryEngine3D() {
    }

    public static TerritoryResult3D analyze(
            List<SpatialObject3D> allObjects,
            RegionMask3D region,
            EdgeCellPolicy edgePolicy) {
        return analyze(allObjects, region, edgePolicy, Cancellation.NEVER);
    }

    /**
     * As {@link #analyze(List, RegionMask3D, EdgeCellPolicy)}, polling
     * {@code cancelled} per slice and every 4,096 voxels (also from worker
     * threads, so it must be thread-safe) and throwing {@link ComputationCancelledException} as soon as it returns
     * {@code true}. The result, when there is one, is bit-identical to the
     * overload without a check. {@code null} never cancels.
     *
     * @since 0.2.1
     */
    public static TerritoryResult3D analyze(
            List<SpatialObject3D> allObjects,
            RegionMask3D region,
            EdgeCellPolicy edgePolicy,
            BooleanSupplier cancelled) {
        BooleanSupplier stop = Cancellation.orNever(cancelled);
        if (allObjects == null) throw new IllegalArgumentException("objects must not be null");
        if (region == null) throw new IllegalArgumentException("region must not be null");
        if (edgePolicy == null) throw new IllegalArgumentException("edge policy must not be null");

        List<SpatialObject3D> objects = objectsInside(allObjects, region);
        assertUniqueCentroids(objects);
        int voxelTotal = region.getWidth() * region.getHeight() * region.getDepth();
        int[] owner = new int[voxelTotal];
        Arrays.fill(owner, -1);
        Cancellation.check(stop);
        assignOwnersByConnectedComponent(owner, objects, region, stop);

        int[] localByGlobal = localByGlobal(objects);
        long[] counts = new long[objects.size()];
        boolean[] edgeCells = new boolean[objects.size()];
        @SuppressWarnings("unchecked")
        Set<Integer>[] neighbors = new Set[objects.size()];
        for (int i = 0; i < neighbors.length; i++) neighbors[i] = new TreeSet<Integer>();
        measure(owner, region, localByGlobal, counts, edgeCells, neighbors, stop);
        Cancellation.check(stop);

        double voxelVolume = region.getPixelWidth()
                * region.getPixelHeight() * region.getPixelDepth();
        ArrayList<TerritoryCell3D> cells =
                new ArrayList<TerritoryCell3D>(objects.size());
        for (int i = 0; i < objects.size(); i++) {
            cells.add(new TerritoryCell3D(
                    objects.get(i),
                    counts[i],
                    counts[i] * voxelVolume,
                    new ArrayList<Integer>(neighbors[i]),
                    edgeCells[i]));
        }
        cells.sort((first, second) ->
                Integer.compare(first.getObject().getIndex(), second.getObject().getIndex()));
        return new TerritoryResult3D(
                region.getName(),
                cells,
                regularity(cells, edgePolicy),
                labelImage(owner, region));
    }

    /** Position in {@code objects} by global object index; -1 for any other index. */
    private static int[] localByGlobal(List<SpatialObject3D> objects) {
        int maximum = -1;
        for (SpatialObject3D object : objects) {
            maximum = Math.max(maximum, object.getIndex());
        }
        int[] result = new int[maximum + 1];
        Arrays.fill(result, -1);
        for (int i = 0; i < objects.size(); i++) {
            result[objects.get(i).getIndex()] = i;
        }
        return result;
    }

    private static int local(int[] localByGlobal, int global) {
        return global < localByGlobal.length ? localByGlobal[global] : -1;
    }

    private static List<SpatialObject3D> objectsInside(
            List<SpatialObject3D> allObjects, RegionMask3D region) {
        ArrayList<SpatialObject3D> result = new ArrayList<SpatialObject3D>();
        for (SpatialObject3D object : allObjects) {
            if (object == null) throw new IllegalArgumentException("objects must not contain null");
            int x = (int) Math.floor(object.getCentroidX() / region.getPixelWidth());
            int y = (int) Math.floor(object.getCentroidY() / region.getPixelHeight());
            int z = (int) Math.floor(object.getCentroidZ() / region.getPixelDepth());
            if (region.contains(x, y, z)) result.add(object);
        }
        return result;
    }

    private static void assertUniqueCentroids(List<SpatialObject3D> objects) {
        Map<CoordinateKey, SpatialObject3D> encountered =
                new LinkedHashMap<CoordinateKey, SpatialObject3D>();
        for (SpatialObject3D object : objects) {
            CoordinateKey key = new CoordinateKey(
                    object.getCentroidX(), object.getCentroidY(), object.getCentroidZ());
            SpatialObject3D previous = encountered.put(key, object);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "objects " + previous.getTypeName() + ":" + previous.getLabel()
                                + " and " + object.getTypeName() + ":" + object.getLabel()
                                + " have identical 3D centroids; a territory is undefined");
            }
        }
    }

    private static void assignOwnersByConnectedComponent(
            int[] owner,
            List<SpatialObject3D> objects,
            RegionMask3D region,
            BooleanSupplier stop) {
        int width = region.getWidth();
        int height = region.getHeight();
        int plane = width * height;
        final int unvisited = -2;
        final int queued = -3;
        for (int index = 0; index < owner.length; index++) {
            if (region.containsIndex(index)) owner[index] = unvisited;
        }
        Map<Integer, List<SpatialObject3D>> objectsByVoxel =
                new HashMap<Integer, List<SpatialObject3D>>();
        for (SpatialObject3D object : objects) {
            int x = (int) Math.floor(object.getCentroidX() / region.getPixelWidth());
            int y = (int) Math.floor(object.getCentroidY() / region.getPixelHeight());
            int z = (int) Math.floor(object.getCentroidZ() / region.getPixelDepth());
            int index = (z * height + y) * width + x;
            List<SpatialObject3D> atVoxel = objectsByVoxel.get(index);
            if (atVoxel == null) {
                atVoxel = new ArrayList<SpatialObject3D>();
                objectsByVoxel.put(index, atVoxel);
            }
            atVoxel.add(object);
        }

        for (int start = 0; start < owner.length; start++) {
            if ((start & Cancellation.POLL_MASK) == 0) Cancellation.check(stop);
            if (owner[start] != unvisited) continue;
            IntBuffer component = new IntBuffer();
            ArrayList<SpatialObject3D> componentObjects =
                    new ArrayList<SpatialObject3D>();
            component.add(start);
            owner[start] = queued;
            for (int cursor = 0; cursor < component.size(); cursor++) {
                if ((cursor & Cancellation.POLL_MASK) == 0) Cancellation.check(stop);
                int index = component.get(cursor);
                List<SpatialObject3D> atVoxel = objectsByVoxel.get(index);
                if (atVoxel != null) componentObjects.addAll(atVoxel);
                int z = index / plane;
                int remainder = index - z * plane;
                int y = remainder / width;
                int x = remainder - y * width;
                queue(owner, component, index - 1, x > 0, unvisited, queued);
                queue(owner, component, index + 1, x + 1 < width, unvisited, queued);
                queue(owner, component, index - width, y > 0, unvisited, queued);
                queue(owner, component, index + width, y + 1 < height, unvisited, queued);
                queue(owner, component, index - plane, z > 0, unvisited, queued);
                queue(
                        owner,
                        component,
                        index + plane,
                        z + 1 < region.getDepth(),
                        unvisited,
                        queued);
            }

            if (componentObjects.isEmpty()) {
                for (int cursor = 0; cursor < component.size(); cursor++) {
                    owner[component.get(cursor)] = -1;
                }
                continue;
            }
            // Each voxel's owner is independent of every other voxel's, so
            // workers take disjoint runs of the component, each with its own
            // query state over the shared tree.
            NearestCentroid3D nearest = new NearestCentroid3D(componentObjects);
            Parallel.forRange(0, component.size(), (from, to) -> {
                NearestCentroid3D.Query query = nearest.query();
                for (int cursor = from; cursor < to; cursor++) {
                    if ((cursor & Cancellation.POLL_MASK) == 0) Cancellation.check(stop);
                    int index = component.get(cursor);
                    int z = index / plane;
                    int remainder = index - z * plane;
                    int y = remainder / width;
                    int x = remainder - y * width;
                    owner[index] = query.nearest(
                            (x + 0.5) * region.getPixelWidth(),
                            (y + 0.5) * region.getPixelHeight(),
                            (z + 0.5) * region.getPixelDepth()).getIndex();
                }
            });
        }
    }

    private static void queue(
            int[] owner,
            IntBuffer component,
            int index,
            boolean inBounds,
            int unvisited,
            int queued) {
        if (!inBounds || owner[index] != unvisited) return;
        owner[index] = queued;
        component.add(index);
    }

    private static void measure(
            int[] owner,
            RegionMask3D region,
            int[] localByGlobal,
            long[] counts,
            boolean[] edgeCells,
            Set<Integer>[] neighbors,
            BooleanSupplier stop) {
        int width = region.getWidth();
        int height = region.getHeight();
        int depth = region.getDepth();
        for (int z = 0; z < depth; z++) {
            Cancellation.check(stop);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = (z * height + y) * width + x;
                    if (!region.containsIndex(index) || owner[index] < 0) continue;
                    int local = local(localByGlobal, owner[index]);
                    if (local < 0) continue;
                    counts[local]++;
                    if (touchesRegionBoundary(region, x, y, z)) edgeCells[local] = true;
                    connect(owner, region, localByGlobal, neighbors, x, y, z, x + 1, y, z);
                    connect(owner, region, localByGlobal, neighbors, x, y, z, x, y + 1, z);
                    connect(owner, region, localByGlobal, neighbors, x, y, z, x, y, z + 1);
                }
            }
        }
    }

    private static boolean touchesRegionBoundary(
            RegionMask3D region, int x, int y, int z) {
        return !region.contains(x - 1, y, z)
                || !region.contains(x + 1, y, z)
                || !region.contains(x, y - 1, z)
                || !region.contains(x, y + 1, z)
                || !region.contains(x, y, z - 1)
                || !region.contains(x, y, z + 1);
    }

    private static void connect(
            int[] owner,
            RegionMask3D region,
            int[] localByGlobal,
            Set<Integer>[] neighbors,
            int x,
            int y,
            int z,
            int otherX,
            int otherY,
            int otherZ) {
        if (!region.contains(otherX, otherY, otherZ)) return;
        int width = region.getWidth();
        int height = region.getHeight();
        int firstIndex = (z * height + y) * width + x;
        int secondIndex = (otherZ * height + otherY) * width + otherX;
        int firstOwner = owner[firstIndex];
        int secondOwner = owner[secondIndex];
        if (firstOwner < 0 || secondOwner < 0 || firstOwner == secondOwner) return;
        int firstLocal = local(localByGlobal, firstOwner);
        int secondLocal = local(localByGlobal, secondOwner);
        if (firstLocal < 0 || secondLocal < 0) return;
        neighbors[firstLocal].add(secondOwner);
        neighbors[secondLocal].add(firstOwner);
    }

    private static ImagePlus labelImage(int[] owner, RegionMask3D region) {
        ImageStack stack = new ImageStack(region.getWidth(), region.getHeight());
        int plane = region.getWidth() * region.getHeight();
        for (int z = 0; z < region.getDepth(); z++) {
            float[] pixels = new float[plane];
            for (int i = 0; i < plane; i++) {
                int global = owner[z * plane + i];
                pixels[i] = global < 0 ? 0.0f : global + 1.0f;
            }
            stack.addSlice(new FloatProcessor(region.getWidth(), region.getHeight(), pixels));
        }
        ImagePlus image = new ImagePlus(region.getName() + "_Territories_3D", stack);
        Calibration calibration = image.getCalibration();
        calibration.pixelWidth = region.getPixelWidth();
        calibration.pixelHeight = region.getPixelHeight();
        calibration.pixelDepth = region.getPixelDepth();
        calibration.setUnit(region.getSpatialUnit());
        return image;
    }

    private static RegularityResult regularity(
            List<TerritoryCell3D> cells, EdgeCellPolicy edgePolicy) {
        ArrayList<TerritoryCell3D> included = new ArrayList<TerritoryCell3D>();
        for (TerritoryCell3D cell : cells) {
            if (edgePolicy == EdgeCellPolicy.INCLUDE_FLAGGED || !cell.isEdgeCell()) {
                included.add(cell);
            }
        }
        if (included.isEmpty()) return emptyRegularity();
        double[] volumes = new double[included.size()];
        double[] nearest = new double[included.size()];
        for (int i = 0; i < included.size(); i++) {
            volumes[i] = included.get(i).getVolume();
            // Border correction: only interior cells are summarised, but every
            // cell stays available as a neighbour candidate. Restricting the
            // candidate set to `included` would inflate the distances.
            nearest[i] = nearestDistance(cells, included.get(i));
        }
        double volumeMean = mean(volumes);
        double volumeSd = sampleStandardDeviation(volumes, volumeMean);
        double nearestMean = meanFinite(nearest);
        double nearestSd = sampleStandardDeviationFinite(nearest, nearestMean);
        return new RegularityResult(
                included.size(),
                volumeMean > 0.0 ? volumeSd / volumeMean : Double.NaN,
                nearestMean,
                nearestSd,
                regularityRatio(nearestMean, nearestSd));
    }

    /**
     * Ratio of mean nearest-neighbour distance to its standard deviation.
     * An undefined spread (fewer than two samples) yields NaN, while a
     * genuinely zero spread across two or more samples yields infinity.
     */
    private static double regularityRatio(double mean, double standardDeviation) {
        if (Double.isNaN(mean) || Double.isNaN(standardDeviation)) return Double.NaN;
        if (standardDeviation > 0.0) return mean / standardDeviation;
        return Double.POSITIVE_INFINITY;
    }

    private static double nearestDistance(
            List<TerritoryCell3D> candidates, TerritoryCell3D source) {
        if (candidates.size() < 2) return Double.NaN;
        SpatialObject3D origin = source.getObject();
        double best = Double.POSITIVE_INFINITY;
        for (TerritoryCell3D candidate : candidates) {
            if (candidate == source) continue;
            SpatialObject3D other = candidate.getObject();
            double dx = origin.getCentroidX() - other.getCentroidX();
            double dy = origin.getCentroidY() - other.getCentroidY();
            double dz = origin.getCentroidZ() - other.getCentroidZ();
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return Double.isFinite(best) ? best : Double.NaN;
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.length;
    }

    private static double sampleStandardDeviation(double[] values, double mean) {
        if (values.length < 2) return Double.NaN;
        double squared = 0.0;
        for (double value : values) {
            double delta = value - mean;
            squared += delta * delta;
        }
        return Math.sqrt(squared / (values.length - 1));
    }

    private static double meanFinite(double[] values) {
        double sum = 0.0;
        int count = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) continue;
            sum += value;
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static double sampleStandardDeviationFinite(double[] values, double mean) {
        if (!Double.isFinite(mean)) return Double.NaN;
        double squared = 0.0;
        int count = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) continue;
            double delta = value - mean;
            squared += delta * delta;
            count++;
        }
        return count < 2 ? Double.NaN : Math.sqrt(squared / (count - 1));
    }

    private static RegularityResult emptyRegularity() {
        return new RegularityResult(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private static final class CoordinateKey {
        private final long x;
        private final long y;
        private final long z;

        private CoordinateKey(double x, double y, double z) {
            this.x = Double.doubleToLongBits(x);
            this.y = Double.doubleToLongBits(y);
            this.z = Double.doubleToLongBits(z);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CoordinateKey)) return false;
            CoordinateKey key = (CoordinateKey) other;
            return x == key.x && y == key.y && z == key.z;
        }

        @Override
        public int hashCode() {
            int result = (int) (x ^ (x >>> 32));
            result = 31 * result + (int) (y ^ (y >>> 32));
            return 31 * result + (int) (z ^ (z >>> 32));
        }
    }

    private static final class IntBuffer {
        private int[] values = new int[1024];
        private int size;

        private void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        private int get(int index) {
            return values[index];
        }

        private int size() {
            return size;
        }
    }
}
