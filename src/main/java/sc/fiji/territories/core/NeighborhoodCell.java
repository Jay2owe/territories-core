package sc.fiji.territories.core;

import java.util.List;

/** Shared graph-node contract for 2D and 3D territory cells. */
public interface NeighborhoodCell {

    TypedSpatialObject getSpatialObject();

    List<Integer> getNeighborObjectIndices();

    boolean isEdgeCell();
}
