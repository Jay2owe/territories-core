package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Two-dimensional Gaussian kernel density estimation in calibrated units.
 *
 * <p>Count maps integrate to the number of admitted objects. Area-weighted maps
 * integrate to their summed calibrated areas. Corrected mode renormalises each
 * truncated kernel over the supplied region, preventing artificial edge loss.
 */
public final class DensityEngine {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double KERNEL_RADIUS_IN_SIGMAS = 3.0;

    private DensityEngine() {
    }

    public static DensityResult generate(
            List<SpatialObject2D> allObjects,
            SpatialRegion2D region,
            String typeName,
            int width,
            int height,
            double pixelWidth,
            double pixelHeight,
            String spatialUnit,
            double requestedBandwidthMicrons,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode) {
        return generate(
                allObjects, region, typeName, width, height, pixelWidth, pixelHeight,
                spatialUnit, requestedBandwidthMicrons, weighting, boundaryMode,
                Cancellation.NEVER);
    }

    /**
     * As {@link #generate(List, SpatialRegion2D, String, int, int, double, double,
     * String, double, DensityWeighting, DensityBoundaryMode)}, polling
     * {@code cancelled} per image row, kernel and object (also from worker
     * threads, so it must be thread-safe) and throwing {@link ComputationCancelledException} as soon as it returns
     * {@code true}. The result, when there is one, is bit-identical to the
     * overload without a check. {@code null} never cancels.
     *
     * @since 0.2.1
     */
    public static DensityResult generate(
            List<SpatialObject2D> allObjects,
            SpatialRegion2D region,
            String typeName,
            int width,
            int height,
            double pixelWidth,
            double pixelHeight,
            String spatialUnit,
            double requestedBandwidthMicrons,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode,
            BooleanSupplier cancelled) {
        BooleanSupplier stop = Cancellation.orNever(cancelled);
        if (allObjects == null) throw new IllegalArgumentException("objects must not be null");
        if (region == null) throw new IllegalArgumentException("region must not be null");
        if (typeName == null || typeName.trim().isEmpty()) {
            throw new IllegalArgumentException("type name must not be empty");
        }
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("density image dimensions are invalid");
        }
        if (!Double.isFinite(pixelWidth) || pixelWidth <= 0.0
                || !Double.isFinite(pixelHeight) || pixelHeight <= 0.0) {
            throw new IllegalArgumentException("pixel calibration must be positive and finite");
        }
        if (weighting == null) throw new IllegalArgumentException("density weighting is required");
        if (boundaryMode == null) throw new IllegalArgumentException("boundary mode is required");
        if (!Double.isFinite(requestedBandwidthMicrons) || requestedBandwidthMicrons < 0.0) {
            throw new IllegalArgumentException("bandwidth must be finite and at least zero");
        }

        Geometry domain = region.geometryReference();
        PreparedGeometry preparedDomain = PreparedGeometryFactory.prepare(domain);
        List<SpatialObject2D> objects = admittedObjects(allObjects, typeName, preparedDomain);
        double bandwidth = requestedBandwidthMicrons > 0.0
                ? requestedBandwidthMicrons
                : automaticBandwidth(objects, pixelWidth, pixelHeight);

        Cancellation.check(stop);
        RasterDomain raster = rasterDomain(
                domain, width, height, pixelWidth, pixelHeight, stop);
        float[] pixels = new float[width * height];
        for (int i = 0; i < pixels.length; i++) {
            if (raster.componentByPixel[i] < 0) pixels[i] = Float.NaN;
        }

        int[] componentOf = new int[objects.size()];
        List<Kernel> kernels = kernels(
                objects, componentOf, raster, width, height, pixelWidth, pixelHeight,
                bandwidth, weighting, boundaryMode, stop);
        accumulate(
                kernels, pixels, raster.componentByPixel, width, height,
                pixelWidth, pixelHeight, bandwidth, stop);

        FloatProcessor processor = new FloatProcessor(width, height, pixels);
        String title = safe(typeName) + "_" + safe(region.getName()) + "_Density";
        ImagePlus image = new ImagePlus(title, processor);
        Calibration calibration = image.getCalibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.setUnit(
                spatialUnit == null || spatialUnit.trim().isEmpty() ? "um" : spatialUnit);

        Map<Integer, Double> localDensity = localDensity(
                objects, componentOf, kernels, bandwidth, stop);
        Cancellation.check(stop);
        return new DensityResult(
                region.getName(), typeName, weighting, boundaryMode,
                bandwidth, image, localDensity);
    }

    private static List<SpatialObject2D> admittedObjects(
            List<SpatialObject2D> allObjects,
            String typeName,
            PreparedGeometry domain) {
        ArrayList<SpatialObject2D> result = new ArrayList<SpatialObject2D>();
        for (SpatialObject2D object : allObjects) {
            if (object == null) throw new IllegalArgumentException("objects must not contain null");
            if (!object.getTypeName().equals(typeName)) continue;
            Point point = GEOMETRY_FACTORY.createPoint(
                    new Coordinate(object.getCentroidX(), object.getCentroidY()));
            if (domain.covers(point)) result.add(object);
        }
        return result;
    }

    private static RasterDomain rasterDomain(
            Geometry domain,
            int width,
            int height,
            double pixelWidth,
            double pixelHeight,
            BooleanSupplier stop) {
        List<Geometry> geometries = polygonalComponents(domain);
        ArrayList<PreparedGeometry> prepared =
                new ArrayList<PreparedGeometry>(geometries.size());
        int[] componentByPixel = new int[width * height];
        Arrays.fill(componentByPixel, -1);
        for (int component = 0; component < geometries.size(); component++) {
            Geometry geometry = geometries.get(component);
            PreparedGeometry preparedGeometry = PreparedGeometryFactory.prepare(geometry);
            prepared.add(preparedGeometry);
            int minimumX = Math.max(
                    0, (int) Math.floor(geometry.getEnvelopeInternal().getMinX() / pixelWidth));
            int maximumX = Math.min(
                    width - 1,
                    (int) Math.ceil(geometry.getEnvelopeInternal().getMaxX() / pixelWidth));
            int minimumY = Math.max(
                    0, (int) Math.floor(geometry.getEnvelopeInternal().getMinY() / pixelHeight));
            int maximumY = Math.min(
                    height - 1,
                    (int) Math.ceil(geometry.getEnvelopeInternal().getMaxY() / pixelHeight));
            final int currentComponent = component;
            // Rows are independent. Each chunk prepares its own copy of the
            // geometry, so no lazily built JTS index is shared between threads.
            Parallel.forRange(minimumY, maximumY + 1, (fromY, toY) -> {
                PreparedGeometry rows = PreparedGeometryFactory.prepare(geometry);
                for (int y = fromY; y < toY; y++) {
                    Cancellation.check(stop);
                    double physicalY = (y + 0.5) * pixelHeight;
                    for (int x = minimumX; x <= maximumX; x++) {
                        int index = y * width + x;
                        if (componentByPixel[index] >= 0) continue;
                        double physicalX = (x + 0.5) * pixelWidth;
                        if (rows.covers(GEOMETRY_FACTORY.createPoint(
                                new Coordinate(physicalX, physicalY)))) {
                            componentByPixel[index] = currentComponent;
                        }
                    }
                }
            });
        }
        return new RasterDomain(componentByPixel, prepared);
    }

    /**
     * Kernels in object order. Region components are located serially (the
     * prepared JTS geometries are shared); kernel support sums run in
     * parallel. The first failure in object order is thrown, exactly as the
     * serial loop would.
     */
    private static List<Kernel> kernels(
            List<SpatialObject2D> objects,
            int[] componentOf,
            RasterDomain raster,
            int width,
            int height,
            double pixelWidth,
            double pixelHeight,
            double bandwidth,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode,
            BooleanSupplier stop) {
        int count = objects.size();
        Kernel[] computed = new Kernel[count];
        RuntimeException[] failures = new RuntimeException[count];
        for (int i = 0; i < count; i++) {
            Cancellation.check(stop);
            try {
                componentOf[i] = componentAtObject(objects.get(i), raster);
            } catch (RuntimeException failure) {
                failures[i] = failure;
            }
        }
        Parallel.forRange(0, count, (from, to) -> {
            for (int i = from; i < to; i++) {
                if (failures[i] != null) continue;
                Cancellation.check(stop);
                try {
                    computed[i] = kernel(
                            objects.get(i), componentOf[i], raster, width, height,
                            pixelWidth, pixelHeight, bandwidth, weighting, boundaryMode);
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
            SpatialObject2D object,
            int component,
            RasterDomain raster,
            int width,
            int height,
            double pixelWidth,
            double pixelHeight,
            double bandwidth,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode) {
        Bounds bounds = bounds(object, width, height, pixelWidth, pixelHeight, bandwidth);
        double weight = weighting == DensityWeighting.OBJECT_COUNT ? 1.0 : object.getArea();
        double supported = gaussianSum(
                object,
                bounds,
                raster.componentByPixel,
                component,
                width,
                pixelWidth,
                pixelHeight,
                bandwidth);
        if (!(supported > 0.0)) {
            throw unsupportedKernel(object, bandwidth, pixelWidth, pixelHeight);
        }
        double denominator;
        if (boundaryMode == DensityBoundaryMode.CORRECTED) {
            denominator = supported * pixelWidth * pixelHeight;
        } else {
            denominator = 2.0 * Math.PI * bandwidth * bandwidth;
        }
        return new Kernel(object, bounds, component, weight / denominator);
    }

    private static IllegalArgumentException unsupportedKernel(
            SpatialObject2D object,
            double bandwidth,
            double pixelWidth,
            double pixelHeight) {
        return new IllegalArgumentException(
                "density kernel for " + object.getTypeName() + ":" + object.getLabel()
                        + " has no sampled support inside its region at bandwidth "
                        + bandwidth + "; increase the bandwidth or use finer image/ROI "
                        + "resolution (pixel size " + pixelWidth + " x " + pixelHeight + ")");
    }

    /**
     * Adds every kernel to the map. Workers own disjoint row bands and visit
     * the kernels in list order, so each pixel receives exactly the serial
     * sequence of float additions.
     */
    private static void accumulate(
            List<Kernel> kernels,
            float[] pixels,
            int[] components,
            int width,
            int height,
            double pixelWidth,
            double pixelHeight,
            double bandwidth,
            BooleanSupplier stop) {
        Parallel.forRange(0, height, (fromY, toY) -> {
            for (Kernel kernel : kernels) {
                Cancellation.check(stop);
                accumulate(
                        kernel, pixels, components, width,
                        pixelWidth, pixelHeight, bandwidth,
                        Math.max(kernel.bounds.minimumY, fromY),
                        Math.min(kernel.bounds.maximumY, toY - 1));
            }
        });
    }

    private static void accumulate(
            Kernel kernel,
            float[] pixels,
            int[] components,
            int width,
            double pixelWidth,
            double pixelHeight,
            double bandwidth,
            int minimumY,
            int maximumY) {
        double inverseTwoBandwidthSquared = 1.0 / (2.0 * bandwidth * bandwidth);
        SpatialObject2D object = kernel.object;
        for (int y = minimumY; y <= maximumY; y++) {
            double dy = (y + 0.5) * pixelHeight - object.getCentroidY();
            for (int x = kernel.bounds.minimumX; x <= kernel.bounds.maximumX; x++) {
                int index = y * width + x;
                if (components[index] != kernel.component) continue;
                double dx = (x + 0.5) * pixelWidth - object.getCentroidX();
                double gaussian = Math.exp(-(dx * dx + dy * dy) * inverseTwoBandwidthSquared);
                pixels[index] += (float) (gaussian * kernel.scale);
            }
        }
    }

    private static double gaussianSum(
            SpatialObject2D object,
            Bounds bounds,
            int[] components,
            int component,
            int width,
            double pixelWidth,
            double pixelHeight,
            double bandwidth) {
        double inverseTwoBandwidthSquared = 1.0 / (2.0 * bandwidth * bandwidth);
        double result = 0.0;
        for (int y = bounds.minimumY; y <= bounds.maximumY; y++) {
            double dy = (y + 0.5) * pixelHeight - object.getCentroidY();
            for (int x = bounds.minimumX; x <= bounds.maximumX; x++) {
                if (components[y * width + x] != component) continue;
                double dx = (x + 0.5) * pixelWidth - object.getCentroidX();
                result += Math.exp(-(dx * dx + dy * dy) * inverseTwoBandwidthSquared);
            }
        }
        return result;
    }

    /** Leave-one-out density at each object, computed in parallel, returned in object order. */
    private static Map<Integer, Double> localDensity(
            List<SpatialObject2D> objects,
            int[] componentOf,
            List<Kernel> kernels,
            double bandwidth,
            BooleanSupplier stop) {
        double inverseTwoBandwidthSquared = 1.0 / (2.0 * bandwidth * bandwidth);
        double radius = KERNEL_RADIUS_IN_SIGMAS * bandwidth;
        double[] densities = new double[objects.size()];
        Parallel.forRange(0, objects.size(), (from, to) -> {
            for (int i = from; i < to; i++) {
                Cancellation.check(stop);
                SpatialObject2D selected = objects.get(i);
                double density = 0.0;
                int selectedComponent = componentOf[i];
                for (Kernel kernel : kernels) {
                    if (kernel.object.getIndex() == selected.getIndex()) continue;
                    if (kernel.component != selectedComponent) continue;
                    double dx = selected.getCentroidX() - kernel.object.getCentroidX();
                    double dy = selected.getCentroidY() - kernel.object.getCentroidY();
                    if (Math.abs(dx) > radius || Math.abs(dy) > radius) continue;
                    density += Math.exp(-(dx * dx + dy * dy) * inverseTwoBandwidthSquared)
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
            SpatialObject2D object, RasterDomain raster) {
        Point point = GEOMETRY_FACTORY.createPoint(
                new Coordinate(object.getCentroidX(), object.getCentroidY()));
        for (int component = 0; component < raster.components.size(); component++) {
            if (raster.components.get(component).covers(point)) return component;
        }
        throw new IllegalStateException(
                "admitted object is not covered by a polygonal region component: "
                        + object.getTypeName() + ":" + object.getLabel());
    }

    private static List<Geometry> polygonalComponents(Geometry geometry) {
        ArrayList<Geometry> result = new ArrayList<Geometry>();
        collectPolygonalComponents(geometry, result);
        return result;
    }

    private static void collectPolygonalComponents(
            Geometry geometry, List<Geometry> destination) {
        if (geometry instanceof Polygon) {
            destination.add(geometry);
            return;
        }
        if (geometry instanceof GeometryCollection) {
            GeometryCollection collection = (GeometryCollection) geometry;
            for (int index = 0; index < collection.getNumGeometries(); index++) {
                collectPolygonalComponents(collection.getGeometryN(index), destination);
            }
        }
    }

    static double automaticBandwidth(
            List<SpatialObject2D> objects, double pixelWidth, double pixelHeight) {
        double minimum = Math.max(pixelWidth, pixelHeight);
        if (objects.size() < 2) return minimum * 3.0;
        double meanX = 0.0;
        double meanY = 0.0;
        for (SpatialObject2D object : objects) {
            meanX += object.getCentroidX();
            meanY += object.getCentroidY();
        }
        meanX /= objects.size();
        meanY /= objects.size();
        double varianceX = 0.0;
        double varianceY = 0.0;
        for (SpatialObject2D object : objects) {
            double dx = object.getCentroidX() - meanX;
            double dy = object.getCentroidY() - meanY;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }
        double scale = (
                Math.sqrt(varianceX / (objects.size() - 1))
                        + Math.sqrt(varianceY / (objects.size() - 1))) / 2.0;
        double scottFactor = Math.pow(objects.size(), -1.0 / 6.0);
        double selected = scale * scottFactor;
        return Double.isFinite(selected) && selected >= minimum ? selected : minimum;
    }

    private static Bounds bounds(
            SpatialObject2D object,
            int width,
            int height,
            double pixelWidth,
            double pixelHeight,
            double bandwidth) {
        double radius = KERNEL_RADIUS_IN_SIGMAS * bandwidth;
        int minimumX = Math.max(0, (int) Math.floor((object.getCentroidX() - radius) / pixelWidth));
        int maximumX = Math.min(
                width - 1, (int) Math.ceil((object.getCentroidX() + radius) / pixelWidth));
        int minimumY = Math.max(0, (int) Math.floor((object.getCentroidY() - radius) / pixelHeight));
        int maximumY = Math.min(
                height - 1, (int) Math.ceil((object.getCentroidY() + radius) / pixelHeight));
        return new Bounds(minimumX, maximumX, minimumY, maximumY);
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static final class Bounds {
        private final int minimumX;
        private final int maximumX;
        private final int minimumY;
        private final int maximumY;

        private Bounds(int minimumX, int maximumX, int minimumY, int maximumY) {
            this.minimumX = minimumX;
            this.maximumX = maximumX;
            this.minimumY = minimumY;
            this.maximumY = maximumY;
        }
    }

    private static final class RasterDomain {
        private final int[] componentByPixel;
        private final List<PreparedGeometry> components;

        private RasterDomain(
                int[] componentByPixel, List<PreparedGeometry> components) {
            this.componentByPixel = componentByPixel;
            this.components = components;
        }
    }

    private static final class Kernel {
        private final SpatialObject2D object;
        private final Bounds bounds;
        private final int component;
        private final double scale;

        private Kernel(
                SpatialObject2D object, Bounds bounds, int component, double scale) {
            this.object = object;
            this.bounds = bounds;
            this.component = component;
            this.scale = scale;
        }
    }
}
