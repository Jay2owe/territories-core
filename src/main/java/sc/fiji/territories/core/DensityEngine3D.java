package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Voxel-resolved, isotropic-in-physical-space 3D Gaussian density estimation.
 */
public final class DensityEngine3D {

    private static final double RADIUS_IN_SIGMAS = 3.0;

    private DensityEngine3D() {
    }

    public static DensityResult3D generate(
            List<SpatialObject3D> allObjects,
            RegionMask3D region,
            String typeName,
            double requestedBandwidth,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode) {
        return generate(
                allObjects, region, typeName, requestedBandwidth, weighting, boundaryMode,
                Cancellation.NEVER);
    }

    /**
     * As {@link #generate(List, RegionMask3D, String, double, DensityWeighting,
     * DensityBoundaryMode)}, polling {@code cancelled} per slice, kernel and
     * object and every 4,096 voxels (also from worker threads, so it must be
     * thread-safe) and throwing {@link ComputationCancelledException} as soon
     * as it returns {@code true}. The result, when there is one, is
     * bit-identical to the overload without a check. {@code null} never
     * cancels.
     *
     * @since 0.2.1
     */
    public static DensityResult3D generate(
            List<SpatialObject3D> allObjects,
            RegionMask3D region,
            String typeName,
            double requestedBandwidth,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode,
            BooleanSupplier cancelled) {
        BooleanSupplier stop = Cancellation.orNever(cancelled);
        if (allObjects == null) throw new IllegalArgumentException("objects must not be null");
        if (region == null) throw new IllegalArgumentException("region must not be null");
        if (typeName == null || typeName.trim().isEmpty()) {
            throw new IllegalArgumentException("type name must not be empty");
        }
        if (!Double.isFinite(requestedBandwidth) || requestedBandwidth < 0.0) {
            throw new IllegalArgumentException("bandwidth must be finite and at least zero");
        }
        if (weighting == null || boundaryMode == null) {
            throw new IllegalArgumentException("weighting and boundary mode are required");
        }

        List<SpatialObject3D> objects = admittedObjects(allObjects, region, typeName);
        Cancellation.check(stop);
        int[] components = connectedComponents(region, stop);
        double bandwidth = requestedBandwidth > 0.0
                ? requestedBandwidth
                : automaticBandwidth(
                        objects,
                        region.getPixelWidth(),
                        region.getPixelHeight(),
                        region.getPixelDepth());
        float[][] pixels = initialiseVolume(region, stop);
        List<Kernel> kernels = kernels(
                objects, region, components, bandwidth, weighting, boundaryMode, stop);
        accumulate(kernels, pixels, region, components, bandwidth, stop);
        Map<Integer, Double> localDensity =
                localDensity(objects, kernels, region, components, bandwidth, stop);
        Cancellation.check(stop);

        ImagePlus image = image(pixels, region, typeName);
        return new DensityResult3D(
                region.getName(),
                typeName,
                weighting,
                boundaryMode,
                bandwidth,
                image,
                localDensity);
    }

    private static List<SpatialObject3D> admittedObjects(
            List<SpatialObject3D> allObjects, RegionMask3D region, String typeName) {
        ArrayList<SpatialObject3D> result = new ArrayList<SpatialObject3D>();
        for (SpatialObject3D object : allObjects) {
            if (object == null) throw new IllegalArgumentException("objects must not contain null");
            if (!object.getTypeName().equals(typeName)) continue;
            int x = (int) Math.floor(object.getCentroidX() / region.getPixelWidth());
            int y = (int) Math.floor(object.getCentroidY() / region.getPixelHeight());
            int z = (int) Math.floor(object.getCentroidZ() / region.getPixelDepth());
            if (region.contains(x, y, z)) result.add(object);
        }
        return result;
    }

    private static float[][] initialiseVolume(RegionMask3D region, BooleanSupplier stop) {
        int plane = region.getWidth() * region.getHeight();
        float[][] result = new float[region.getDepth()][plane];
        for (int z = 0; z < region.getDepth(); z++) {
            Cancellation.check(stop);
            for (int y = 0; y < region.getHeight(); y++) {
                for (int x = 0; x < region.getWidth(); x++) {
                    if (!region.contains(x, y, z)) {
                        result[z][y * region.getWidth() + x] = Float.NaN;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Kernels in object order, their support sums computed in parallel. The
     * first failure in object order is thrown, exactly as the serial loop
     * would.
     */
    private static List<Kernel> kernels(
            List<SpatialObject3D> objects,
            RegionMask3D region,
            int[] components,
            double bandwidth,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode,
            BooleanSupplier stop) {
        int count = objects.size();
        Kernel[] computed = new Kernel[count];
        RuntimeException[] failures = new RuntimeException[count];
        Parallel.forRange(0, count, (from, to) -> {
            for (int i = from; i < to; i++) {
                Cancellation.check(stop);
                try {
                    computed[i] = kernel(
                            objects.get(i), region, components, bandwidth,
                            weighting, boundaryMode);
                } catch (RuntimeException failure) {
                    failures[i] = failure;
                }
            }
        });
        ArrayList<Kernel> kernels = new ArrayList<Kernel>(count);
        for (int i = 0; i < count; i++) {
            if (failures[i] != null) throw failures[i];
            if (computed[i] != null) kernels.add(computed[i]);
        }
        return kernels;
    }

    private static Kernel kernel(
            SpatialObject3D object,
            RegionMask3D region,
            int[] components,
            double bandwidth,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode) {
        Bounds bounds = bounds(object, region, bandwidth);
        int component = componentAtObject(object, region, components);
        double weight = weighting == DensityWeighting.OBJECT_COUNT
                ? 1.0 : object.getVolume();
        double supported = gaussianSum(
                object, bounds, region, components, component, bandwidth);
        if (!(supported > 0.0)) {
            throw unsupportedKernel(object, region, bandwidth);
        }
        double denominator;
        if (boundaryMode == DensityBoundaryMode.CORRECTED) {
            denominator = supported
                    * region.getPixelWidth()
                    * region.getPixelHeight()
                    * region.getPixelDepth();
        } else {
            denominator = Math.pow(2.0 * Math.PI, 1.5)
                    * bandwidth * bandwidth * bandwidth;
        }
        return new Kernel(object, bounds, component, weight / denominator);
    }

    private static IllegalArgumentException unsupportedKernel(
            SpatialObject3D object, RegionMask3D region, double bandwidth) {
        return new IllegalArgumentException(
                "3D density kernel for " + object.getTypeName() + ":" + object.getLabel()
                        + " has no sampled support inside its region at bandwidth "
                        + bandwidth + "; increase the bandwidth or use finer voxel resolution "
                        + "(voxel size " + region.getPixelWidth() + " x "
                        + region.getPixelHeight() + " x " + region.getPixelDepth() + ")");
    }

    /**
     * Adds every kernel to the volume. The volume is split into bands of
     * whole image rows (row {@code z * height + y}); workers own disjoint
     * bands and visit the kernels in list order, so each voxel receives
     * exactly the serial sequence of float additions.
     */
    private static void accumulate(
            List<Kernel> kernels,
            float[][] pixels,
            RegionMask3D region,
            int[] components,
            double bandwidth,
            BooleanSupplier stop) {
        int height = region.getHeight();
        Parallel.forRange(0, region.getDepth() * height, (fromRow, toRow) -> {
            int firstZ = fromRow / height;
            int lastZ = (toRow - 1) / height;
            for (Kernel kernel : kernels) {
                Cancellation.check(stop);
                int fromZ = Math.max(kernel.bounds.minimumZ, firstZ);
                int toZ = Math.min(kernel.bounds.maximumZ, lastZ);
                for (int z = fromZ; z <= toZ; z++) {
                    int bandFromY = z == firstZ ? fromRow - z * height : 0;
                    int bandToY = z == lastZ ? toRow - 1 - z * height : height - 1;
                    accumulate(
                            kernel, pixels, region, components, bandwidth, z,
                            Math.max(kernel.bounds.minimumY, bandFromY),
                            Math.min(kernel.bounds.maximumY, bandToY));
                }
            }
        });
    }

    /** One z-slice of one kernel, rows {@code minimumY..maximumY}. */
    private static void accumulate(
            Kernel kernel,
            float[][] pixels,
            RegionMask3D region,
            int[] components,
            double bandwidth,
            int z,
            int minimumY,
            int maximumY) {
        double inverseTwoBandwidthSquared = 1.0 / (2.0 * bandwidth * bandwidth);
        SpatialObject3D object = kernel.object;
        double dz = (z + 0.5) * region.getPixelDepth() - object.getCentroidZ();
        for (int y = minimumY; y <= maximumY; y++) {
            double dy = (y + 0.5) * region.getPixelHeight() - object.getCentroidY();
            for (int x = kernel.bounds.minimumX; x <= kernel.bounds.maximumX; x++) {
                int index = (z * region.getHeight() + y) * region.getWidth() + x;
                if (components[index] != kernel.component) continue;
                double dx = (x + 0.5) * region.getPixelWidth() - object.getCentroidX();
                double gaussian = Math.exp(
                        -(dx * dx + dy * dy + dz * dz) * inverseTwoBandwidthSquared);
                pixels[z][y * region.getWidth() + x] +=
                        (float) (gaussian * kernel.scale);
            }
        }
    }

    private static double gaussianSum(
            SpatialObject3D object,
            Bounds bounds,
            RegionMask3D region,
            int[] components,
            int component,
            double bandwidth) {
        double inverseTwoBandwidthSquared = 1.0 / (2.0 * bandwidth * bandwidth);
        double result = 0.0;
        for (int z = bounds.minimumZ; z <= bounds.maximumZ; z++) {
            double dz = (z + 0.5) * region.getPixelDepth() - object.getCentroidZ();
            for (int y = bounds.minimumY; y <= bounds.maximumY; y++) {
                double dy = (y + 0.5) * region.getPixelHeight() - object.getCentroidY();
                for (int x = bounds.minimumX; x <= bounds.maximumX; x++) {
                    int index = (z * region.getHeight() + y) * region.getWidth() + x;
                    if (components[index] != component) continue;
                    double dx = (x + 0.5) * region.getPixelWidth() - object.getCentroidX();
                    result += Math.exp(
                            -(dx * dx + dy * dy + dz * dz) * inverseTwoBandwidthSquared);
                }
            }
        }
        return result;
    }

    /** Leave-one-out density at each object, computed in parallel, returned in object order. */
    private static Map<Integer, Double> localDensity(
            List<SpatialObject3D> objects,
            List<Kernel> kernels,
            RegionMask3D region,
            int[] components,
            double bandwidth,
            BooleanSupplier stop) {
        double inverseTwoBandwidthSquared = 1.0 / (2.0 * bandwidth * bandwidth);
        double radius = RADIUS_IN_SIGMAS * bandwidth;
        double[] densities = new double[objects.size()];
        Parallel.forRange(0, objects.size(), (from, to) -> {
            for (int i = from; i < to; i++) {
                Cancellation.check(stop);
                SpatialObject3D selected = objects.get(i);
                double density = 0.0;
                int selectedComponent = componentAtObject(selected, region, components);
                for (Kernel kernel : kernels) {
                    if (kernel.object.getIndex() == selected.getIndex()) continue;
                    if (kernel.component != selectedComponent) continue;
                    double dx = selected.getCentroidX() - kernel.object.getCentroidX();
                    double dy = selected.getCentroidY() - kernel.object.getCentroidY();
                    double dz = selected.getCentroidZ() - kernel.object.getCentroidZ();
                    if (Math.abs(dx) > radius
                            || Math.abs(dy) > radius
                            || Math.abs(dz) > radius) {
                        continue;
                    }
                    density += Math.exp(
                            -(dx * dx + dy * dy + dz * dz) * inverseTwoBandwidthSquared)
                            * kernel.scale;
                }
                densities[i] = density;
            }
        });
        LinkedHashMap<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (int i = 0; i < objects.size(); i++) {
            result.put(objects.get(i).getIndex(), densities[i]);
        }
        return result;
    }

    private static int componentAtObject(
            SpatialObject3D object, RegionMask3D region, int[] components) {
        int x = (int) Math.floor(object.getCentroidX() / region.getPixelWidth());
        int y = (int) Math.floor(object.getCentroidY() / region.getPixelHeight());
        int z = (int) Math.floor(object.getCentroidZ() / region.getPixelDepth());
        return components[(z * region.getHeight() + y) * region.getWidth() + x];
    }

    private static int[] connectedComponents(RegionMask3D region, BooleanSupplier stop) {
        int width = region.getWidth();
        int height = region.getHeight();
        int depth = region.getDepth();
        int plane = width * height;
        int total = plane * depth;
        int[] components = new int[total];
        Arrays.fill(components, -1);
        int[] queue = new int[total];
        int component = 0;
        for (int start = 0; start < total; start++) {
            if ((start & Cancellation.POLL_MASK) == 0) Cancellation.check(stop);
            if (!region.containsIndex(start) || components[start] >= 0) continue;
            int read = 0;
            int write = 0;
            queue[write++] = start;
            components[start] = component;
            while (read < write) {
                if ((read & Cancellation.POLL_MASK) == 0) Cancellation.check(stop);
                int index = queue[read++];
                int z = index / plane;
                int remainder = index - z * plane;
                int y = remainder / width;
                int x = remainder - y * width;
                write = enqueue(
                        index - 1, x > 0, component, components, queue, write, region);
                write = enqueue(
                        index + 1, x + 1 < width, component, components, queue, write, region);
                write = enqueue(
                        index - width, y > 0, component, components, queue, write, region);
                write = enqueue(
                        index + width,
                        y + 1 < height,
                        component,
                        components,
                        queue,
                        write,
                        region);
                write = enqueue(
                        index - plane, z > 0, component, components, queue, write, region);
                write = enqueue(
                        index + plane,
                        z + 1 < depth,
                        component,
                        components,
                        queue,
                        write,
                        region);
            }
            component++;
        }
        return components;
    }

    private static int enqueue(
            int index,
            boolean inBounds,
            int component,
            int[] components,
            int[] queue,
            int write,
            RegionMask3D region) {
        if (!inBounds || components[index] >= 0 || !region.containsIndex(index)) {
            return write;
        }
        components[index] = component;
        queue[write] = index;
        return write + 1;
    }

    static double automaticBandwidth(
            List<SpatialObject3D> objects,
            double pixelWidth,
            double pixelHeight,
            double pixelDepth) {
        double minimum = Math.max(pixelWidth, Math.max(pixelHeight, pixelDepth));
        if (objects.size() < 2) return minimum * 3.0;
        double meanX = 0.0;
        double meanY = 0.0;
        double meanZ = 0.0;
        for (SpatialObject3D object : objects) {
            meanX += object.getCentroidX();
            meanY += object.getCentroidY();
            meanZ += object.getCentroidZ();
        }
        meanX /= objects.size();
        meanY /= objects.size();
        meanZ /= objects.size();
        double varianceX = 0.0;
        double varianceY = 0.0;
        double varianceZ = 0.0;
        for (SpatialObject3D object : objects) {
            double dx = object.getCentroidX() - meanX;
            double dy = object.getCentroidY() - meanY;
            double dz = object.getCentroidZ() - meanZ;
            varianceX += dx * dx;
            varianceY += dy * dy;
            varianceZ += dz * dz;
        }
        double denominator = objects.size() - 1.0;
        double scale = (
                Math.sqrt(varianceX / denominator)
                        + Math.sqrt(varianceY / denominator)
                        + Math.sqrt(varianceZ / denominator)) / 3.0;
        double selected = scale * Math.pow(objects.size(), -1.0 / 7.0);
        return Double.isFinite(selected) && selected >= minimum ? selected : minimum;
    }

    private static Bounds bounds(
            SpatialObject3D object, RegionMask3D region, double bandwidth) {
        double radius = RADIUS_IN_SIGMAS * bandwidth;
        return new Bounds(
                Math.max(0, (int) Math.floor(
                        (object.getCentroidX() - radius) / region.getPixelWidth())),
                Math.min(region.getWidth() - 1, (int) Math.ceil(
                        (object.getCentroidX() + radius) / region.getPixelWidth())),
                Math.max(0, (int) Math.floor(
                        (object.getCentroidY() - radius) / region.getPixelHeight())),
                Math.min(region.getHeight() - 1, (int) Math.ceil(
                        (object.getCentroidY() + radius) / region.getPixelHeight())),
                Math.max(0, (int) Math.floor(
                        (object.getCentroidZ() - radius) / region.getPixelDepth())),
                Math.min(region.getDepth() - 1, (int) Math.ceil(
                        (object.getCentroidZ() + radius) / region.getPixelDepth())));
    }

    private static ImagePlus image(
            float[][] pixels, RegionMask3D region, String typeName) {
        ImageStack stack = new ImageStack(region.getWidth(), region.getHeight());
        for (float[] plane : pixels) {
            stack.addSlice(new FloatProcessor(region.getWidth(), region.getHeight(), plane));
        }
        ImagePlus image = new ImagePlus(
                safe(typeName) + "_" + safe(region.getName()) + "_Density_3D", stack);
        Calibration calibration = image.getCalibration();
        calibration.pixelWidth = region.getPixelWidth();
        calibration.pixelHeight = region.getPixelHeight();
        calibration.pixelDepth = region.getPixelDepth();
        calibration.setUnit(region.getSpatialUnit());
        return image;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static final class Bounds {
        private final int minimumX;
        private final int maximumX;
        private final int minimumY;
        private final int maximumY;
        private final int minimumZ;
        private final int maximumZ;

        private Bounds(
                int minimumX,
                int maximumX,
                int minimumY,
                int maximumY,
                int minimumZ,
                int maximumZ) {
            this.minimumX = minimumX;
            this.maximumX = maximumX;
            this.minimumY = minimumY;
            this.maximumY = maximumY;
            this.minimumZ = minimumZ;
            this.maximumZ = maximumZ;
        }
    }

    private static final class Kernel {
        private final SpatialObject3D object;
        private final Bounds bounds;
        private final int component;
        private final double scale;

        private Kernel(
                SpatialObject3D object, Bounds bounds, int component, double scale) {
            this.object = object;
            this.bounds = bounds;
            this.component = component;
            this.scale = scale;
        }
    }
}
