package sc.fiji.territories.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One voxel-resolved 3D territory and its face-sharing neighbours. */
public final class TerritoryCell3D implements NeighborhoodCell {

    private final SpatialObject3D object;
    private final long voxelCount;
    private final double volume;
    private final List<Integer> neighborObjectIndices;
    private final boolean edgeCell;

    TerritoryCell3D(
            SpatialObject3D object,
            long voxelCount,
            double volume,
            List<Integer> neighborObjectIndices,
            boolean edgeCell) {
        this.object = object;
        this.voxelCount = voxelCount;
        this.volume = volume;
        this.neighborObjectIndices = Collections.unmodifiableList(
                new ArrayList<Integer>(neighborObjectIndices));
        this.edgeCell = edgeCell;
    }

    public SpatialObject3D getObject() {
        return object;
    }

    @Override
    public TypedSpatialObject getSpatialObject() {
        return object;
    }

    public long getVoxelCount() {
        return voxelCount;
    }

    public double getVolume() {
        return volume;
    }

    @Override
    public List<Integer> getNeighborObjectIndices() {
        return neighborObjectIndices;
    }

    @Override
    public boolean isEdgeCell() {
        return edgeCell;
    }
}
