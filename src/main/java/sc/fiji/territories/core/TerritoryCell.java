package sc.fiji.territories.core;

import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One bounded Voronoi territory and its measured neighbourhood. */
public final class TerritoryCell implements NeighborhoodCell {

    private final SpatialObject2D object;
    private final Geometry geometry;
    private final List<Integer> neighborObjectIndices;
    private final boolean edgeCell;

    TerritoryCell(
            SpatialObject2D object,
            Geometry geometry,
            List<Integer> neighborObjectIndices,
            boolean edgeCell) {
        this.object = object;
        this.geometry = geometry.copy();
        this.neighborObjectIndices = Collections.unmodifiableList(
                new ArrayList<Integer>(neighborObjectIndices));
        this.edgeCell = edgeCell;
    }

    public SpatialObject2D getObject() {
        return object;
    }

    @Override
    public TypedSpatialObject getSpatialObject() {
        return object;
    }

    public Geometry getGeometry() {
        return geometry.copy();
    }

    public double getArea() {
        return geometry.getArea();
    }

    public List<Integer> getNeighborObjectIndices() {
        return neighborObjectIndices;
    }

    @Override
    public boolean isEdgeCell() {
        return edgeCell;
    }
}
