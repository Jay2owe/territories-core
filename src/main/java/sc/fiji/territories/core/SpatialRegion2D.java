package sc.fiji.territories.core;

import org.locationtech.jts.geom.Geometry;

/** Immutable named two-dimensional spatial domain in calibrated coordinates. */
public final class SpatialRegion2D {

    private final String name;
    private final Geometry geometry;

    public SpatialRegion2D(String name, Geometry geometry) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("region name must not be empty");
        }
        if (geometry == null || geometry.isEmpty() || geometry.getDimension() != 2) {
            throw new IllegalArgumentException("region geometry must have non-empty area");
        }
        this.name = name;
        this.geometry = geometry.copy();
    }

    public String getName() {
        return name;
    }

    public Geometry getGeometry() {
        return geometry.copy();
    }

    Geometry geometryReference() {
        return geometry;
    }
}

