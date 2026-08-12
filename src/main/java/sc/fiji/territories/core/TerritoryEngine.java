package sc.fiji.territories.core;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.triangulate.DelaunayTriangulationBuilder;
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder;
import org.locationtech.jts.triangulate.quadedge.LocateFailureException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact two-dimensional Voronoi engine clipped to an arbitrary region geometry. */
public final class TerritoryEngine {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private TerritoryEngine() {
    }

    public static TerritoryResult analyze(
            List<SpatialObject2D> allObjects,
            SpatialRegion2D region,
            EdgeCellPolicy edgePolicy) {
        if (allObjects == null) throw new IllegalArgumentException("objects must not be null");
        if (region == null) throw new IllegalArgumentException("region must not be null");
        if (edgePolicy == null) throw new IllegalArgumentException("edge policy must not be null");

        try {
            return tessellate(allObjects, region, edgePolicy);
        } catch (TopologyException | LocateFailureException failure) {
            // Near-coincident centroids defeat the exact duplicate guard, and a
            // self-touching region outline fails the same way, so do not claim
            // to know which it was.
            throw new IllegalArgumentException(unresolvable(region, failure), failure);
        }
    }

    private static String unresolvable(SpatialRegion2D region, RuntimeException failure) {
        return "could not tessellate region '" + region.getName()
                + "': the object centroids or the region boundary are too close"
                + " to resolve. Merge or remove near-duplicate objects, or"
                + " simplify the region outline, and run again ("
                + failure.getMessage() + ")";
    }

    private static TerritoryResult tessellate(
            List<SpatialObject2D> allObjects,
            SpatialRegion2D region,
            EdgeCellPolicy edgePolicy) {
        Geometry domain = region.geometryReference();
        List<SpatialObject2D> regionObjects = objectsInside(allObjects, domain);
        assertUniqueCentroids(regionObjects);
        if (regionObjects.isEmpty()) {
            return new TerritoryResult(
                    region.getName(),
                    Collections.<TerritoryCell>emptyList(),
                    emptyRegularity());
        }

        ArrayList<TerritoryCell> results = new ArrayList<TerritoryCell>(regionObjects.size());
        List<Geometry> components = polygonalComponents(domain);
        List<List<SpatialObject2D>> objectsByComponent =
                assignObjectsToComponents(regionObjects, components);
        for (int componentIndex = 0; componentIndex < components.size(); componentIndex++) {
            Geometry component = components.get(componentIndex);
            List<SpatialObject2D> objects = objectsByComponent.get(componentIndex);
            if (objects.isEmpty()) continue;
            Geometry[] cells;
            List<List<Integer>> neighbors;
            if (objects.size() == 1) {
                cells = new Geometry[] {component.copy()};
                neighbors = new ArrayList<List<Integer>>(1);
                neighbors.add(new ArrayList<Integer>());
            } else {
                Collection<Coordinate> sites = new ArrayList<Coordinate>(objects.size());
                for (SpatialObject2D object : objects) {
                    sites.add(new Coordinate(object.getCentroidX(), object.getCentroidY()));
                }

                VoronoiDiagramBuilder builder = new VoronoiDiagramBuilder();
                builder.setSites(sites);
                builder.setClipEnvelope(component.getEnvelopeInternal());
                Geometry diagram = builder.getDiagram(GEOMETRY_FACTORY);
                cells = matchAndClipCells(diagram, objects, component, region);
                neighbors = neighbors(objects, cells);
            }

            double tolerance = tolerance(component);
            Geometry regionBoundary = component.getBoundary();
            for (int i = 0; i < objects.size(); i++) {
                Geometry cell = cells[i];
                Geometry boundaryContact = cell.getBoundary().intersection(regionBoundary);
                boolean edge = !boundaryContact.isEmpty()
                        && cell.getBoundary().distance(regionBoundary) <= tolerance;
                results.add(new TerritoryCell(objects.get(i), cell, neighbors.get(i), edge));
            }
        }
        results.sort(Comparator.comparingInt(value -> value.getObject().getIndex()));
        return new TerritoryResult(region.getName(), results, regularity(results, edgePolicy));
    }

    private static List<List<SpatialObject2D>> assignObjectsToComponents(
            List<SpatialObject2D> objects, List<Geometry> components) {
        ArrayList<List<SpatialObject2D>> result =
                new ArrayList<List<SpatialObject2D>>(components.size());
        for (int index = 0; index < components.size(); index++) {
            result.add(new ArrayList<SpatialObject2D>());
        }
        for (SpatialObject2D object : objects) {
            Point point = GEOMETRY_FACTORY.createPoint(
                    new Coordinate(object.getCentroidX(), object.getCentroidY()));
            boolean assigned = false;
            for (int index = 0; index < components.size(); index++) {
                if (!components.get(index).covers(point)) continue;
                result.get(index).add(object);
                assigned = true;
                break;
            }
            if (!assigned) {
                throw new IllegalStateException(
                        "admitted object is not covered by a polygonal region component: "
                                + object.getTypeName() + ":" + object.getLabel());
            }
        }
        return result;
    }

    private static List<SpatialObject2D> objectsInside(
            List<SpatialObject2D> allObjects, Geometry domain) {
        ArrayList<SpatialObject2D> inside = new ArrayList<SpatialObject2D>();
        for (SpatialObject2D object : allObjects) {
            if (object == null) throw new IllegalArgumentException("objects must not contain null");
            Point point = GEOMETRY_FACTORY.createPoint(
                    new Coordinate(object.getCentroidX(), object.getCentroidY()));
            if (domain.covers(point)) inside.add(object);
        }
        return inside;
    }

    private static List<Geometry> polygonalComponents(Geometry geometry) {
        ArrayList<Geometry> result = new ArrayList<Geometry>();
        collectPolygonalComponents(geometry, result);
        return result;
    }

    private static void collectPolygonalComponents(Geometry geometry, List<Geometry> output) {
        if (geometry instanceof Polygon) {
            output.add(geometry);
            return;
        }
        if (geometry instanceof GeometryCollection) {
            GeometryCollection collection = (GeometryCollection) geometry;
            for (int i = 0; i < collection.getNumGeometries(); i++) {
                collectPolygonalComponents(collection.getGeometryN(i), output);
            }
        }
    }

    private static void assertUniqueCentroids(List<SpatialObject2D> objects) {
        Map<CoordinateKey, SpatialObject2D> encountered =
                new LinkedHashMap<CoordinateKey, SpatialObject2D>();
        for (SpatialObject2D object : objects) {
            CoordinateKey key = new CoordinateKey(object.getCentroidX(), object.getCentroidY());
            SpatialObject2D previous = encountered.put(key, object);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "objects " + previous.getTypeName() + ":" + previous.getLabel()
                                + " and " + object.getTypeName() + ":" + object.getLabel()
                                + " have identical centroids; a Voronoi territory is undefined");
            }
        }
    }

    private static Geometry[] matchAndClipCells(
            Geometry diagram, List<SpatialObject2D> objects, Geometry domain,
            SpatialRegion2D region) {
        Geometry[] result = new Geometry[objects.size()];
        if (!(diagram instanceof GeometryCollection)) {
            throw new IllegalStateException("JTS did not return a Voronoi geometry collection");
        }
        GeometryCollection collection = (GeometryCollection) diagram;
        for (int g = 0; g < collection.getNumGeometries(); g++) {
            Geometry rawCell = collection.getGeometryN(g);
            for (int i = 0; i < objects.size(); i++) {
                if (result[i] != null) continue;
                SpatialObject2D object = objects.get(i);
                Point point = GEOMETRY_FACTORY.createPoint(
                        new Coordinate(object.getCentroidX(), object.getCentroidY()));
                if (rawCell.covers(point)) {
                    // A cell wall lying along the region boundary makes the
                    // overlay hand back that wall as a line beside the cell.
                    Geometry clipped = Polygons.polygonalOnly(rawCell.intersection(domain));
                    if (!clipped.isValid()) clipped = clipped.buffer(0.0);
                    result[i] = clipped;
                    break;
                }
            }
        }
        for (int i = 0; i < result.length; i++) {
            // A clip that collapsed to a line is neither null nor empty, but it
            // is not a territory either — it would be reported with zero area.
            if (result[i] == null || result[i].isEmpty() || result[i].getArea() <= 0.0) {
                // Two centroids sharing one raw Voronoi cell leave the second
                // object with nothing — the same degenerate input the caller
                // explains, so give it the same explanation.
                SpatialObject2D object = objects.get(i);
                throw new IllegalArgumentException(unresolvable(
                        region,
                        new IllegalStateException(
                                "no bounded territory for " + object.getTypeName()
                                        + ":" + object.getLabel())));
            }
        }
        return result;
    }

    private static List<List<Integer>> neighbors(
            List<SpatialObject2D> objects, Geometry[] cells) {
        ArrayList<List<Integer>> neighbors = new ArrayList<List<Integer>>(objects.size());
        for (int i = 0; i < objects.size(); i++) {
            neighbors.add(new ArrayList<Integer>());
        }

        Collection<Coordinate> sites = new ArrayList<Coordinate>(objects.size());
        for (SpatialObject2D object : objects) {
            sites.add(new Coordinate(object.getCentroidX(), object.getCentroidY()));
        }
        DelaunayTriangulationBuilder builder = new DelaunayTriangulationBuilder();
        builder.setSites(sites);
        Geometry edges = builder.getEdges(GEOMETRY_FACTORY);
        if (!(edges instanceof GeometryCollection)) return neighbors;

        double tolerance = tolerance(edges);
        GeometryCollection collection = (GeometryCollection) edges;
        for (int e = 0; e < collection.getNumGeometries(); e++) {
            Coordinate[] coordinates = collection.getGeometryN(e).getCoordinates();
            if (coordinates.length < 2) continue;
            int first = closest(objects, coordinates[0]);
            int second = closest(objects, coordinates[coordinates.length - 1]);
            if (first < 0 || second < 0 || first == second) continue;

            Geometry sharedBoundary = cells[first].getBoundary().intersection(cells[second].getBoundary());
            if (sharedBoundary.getLength() <= tolerance) continue;
            int firstGlobal = objects.get(first).getIndex();
            int secondGlobal = objects.get(second).getIndex();
            if (!neighbors.get(first).contains(secondGlobal)) neighbors.get(first).add(secondGlobal);
            if (!neighbors.get(second).contains(firstGlobal)) neighbors.get(second).add(firstGlobal);
        }
        for (List<Integer> list : neighbors) Collections.sort(list);
        return neighbors;
    }

    private static int closest(List<SpatialObject2D> objects, Coordinate coordinate) {
        int best = -1;
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < objects.size(); i++) {
            SpatialObject2D object = objects.get(i);
            double dx = object.getCentroidX() - coordinate.x;
            double dy = object.getCentroidY() - coordinate.y;
            double squared = dx * dx + dy * dy;
            if (squared < distance) {
                distance = squared;
                best = i;
            }
        }
        return best;
    }

    private static RegularityResult regularity(
            List<TerritoryCell> cells, EdgeCellPolicy edgePolicy) {
        ArrayList<TerritoryCell> included = new ArrayList<TerritoryCell>();
        for (TerritoryCell cell : cells) {
            if (edgePolicy == EdgeCellPolicy.INCLUDE_FLAGGED || !cell.isEdgeCell()) {
                included.add(cell);
            }
        }
        if (included.isEmpty()) return emptyRegularity();

        double[] areas = new double[included.size()];
        double[] nearest = new double[included.size()];
        for (int i = 0; i < included.size(); i++) {
            areas[i] = included.get(i).getArea();
            // Border correction: only interior cells are summarised, but every
            // cell stays available as a neighbour candidate. Restricting the
            // candidate set to `included` would inflate the distances.
            nearest[i] = nearestDistance(cells, included.get(i));
        }
        double areaMean = mean(areas);
        double areaSd = sampleStandardDeviation(areas, areaMean);
        double nnMean = meanFinite(nearest);
        double nnSd = sampleStandardDeviationFinite(nearest, nnMean);
        return new RegularityResult(
                included.size(),
                areaMean > 0.0 ? areaSd / areaMean : Double.NaN,
                nnMean,
                nnSd,
                regularityRatio(nnMean, nnSd));
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

    private static double nearestDistance(List<TerritoryCell> candidates, TerritoryCell source) {
        if (candidates.size() < 2) return Double.NaN;
        SpatialObject2D origin = source.getObject();
        double best = Double.POSITIVE_INFINITY;
        for (TerritoryCell candidate : candidates) {
            if (candidate == source) continue;
            SpatialObject2D other = candidate.getObject();
            double dx = origin.getCentroidX() - other.getCentroidX();
            double dy = origin.getCentroidY() - other.getCentroidY();
            best = Math.min(best, Math.hypot(dx, dy));
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
        double sum = 0.0;
        for (double value : values) {
            double delta = value - mean;
            sum += delta * delta;
        }
        return Math.sqrt(sum / (values.length - 1));
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
        double sum = 0.0;
        int count = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) continue;
            double delta = value - mean;
            sum += delta * delta;
            count++;
        }
        return count < 2 ? Double.NaN : Math.sqrt(sum / (count - 1));
    }

    private static RegularityResult emptyRegularity() {
        return new RegularityResult(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private static double tolerance(Geometry geometry) {
        double scale = Math.max(
                geometry.getEnvelopeInternal().getWidth(),
                geometry.getEnvelopeInternal().getHeight());
        return Math.max(1.0, scale) * 1.0e-9;
    }

    private static final class CoordinateKey {
        private final long x;
        private final long y;

        private CoordinateKey(double x, double y) {
            this.x = Double.doubleToLongBits(x);
            this.y = Double.doubleToLongBits(y);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CoordinateKey)) return false;
            CoordinateKey key = (CoordinateKey) other;
            return x == key.x && y == key.y;
        }

        @Override
        public int hashCode() {
            int result = (int) (x ^ (x >>> 32));
            return 31 * result + (int) (y ^ (y >>> 32));
        }
    }
}
