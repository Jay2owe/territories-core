package sc.fiji.territories.core;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps overlay results usable as analysis domains.
 *
 * <p>Where two polygon boundaries run along each other, JTS returns the shared
 * edge as a dangling line beside the polygon. The resulting mixed
 * {@code GeometryCollection} still reports an area, so it passes the obvious
 * emptiness checks, but every {@code covers}, {@code getBoundary} and
 * {@code intersection} call downstream rejects it outright. Both the region
 * clip and the per-cell territory clip can produce one, so the filter lives in
 * a single place.
 */
final class Polygons {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private Polygons() {
    }

    /**
     * Returns the polygonal part of a geometry as a Polygon or MultiPolygon,
     * or the geometry unchanged when it holds no polygon at all — callers
     * report emptiness themselves, with the context to explain it.
     */
    static Geometry polygonalOnly(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) return geometry;
        if (geometry instanceof Polygon) return geometry;
        ArrayList<Polygon> parts = new ArrayList<Polygon>();
        collect(geometry, parts);
        if (parts.isEmpty()) return geometry;
        if (parts.size() == 1) return parts.get(0);
        return GEOMETRY_FACTORY.createMultiPolygon(parts.toArray(new Polygon[parts.size()]));
    }

    private static void collect(Geometry geometry, List<Polygon> parts) {
        if (geometry == null || geometry.isEmpty()) return;
        if (geometry instanceof Polygon) {
            parts.add((Polygon) geometry);
            return;
        }
        if (geometry instanceof GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                collect(geometry.getGeometryN(i), parts);
            }
        }
    }
}
