package sc.fiji.territories.core;

/** Immutable calibrated object measurement extracted from a 3D label image. */
public final class SpatialObject3D implements TypedSpatialObject {

    private final int index;
    private final int typeIndex;
    private final String typeName;
    private final long label;
    private final double centroidX;
    private final double centroidY;
    private final double centroidZ;
    private final double volume;

    public SpatialObject3D(
            int index,
            int typeIndex,
            String typeName,
            long label,
            double centroidX,
            double centroidY,
            double centroidZ,
            double volume) {
        if (index < 0 || typeIndex < 0) {
            throw new IllegalArgumentException("indices must be non-negative");
        }
        if (typeName == null || typeName.trim().isEmpty()) {
            throw new IllegalArgumentException("typeName must not be empty");
        }
        if (label <= 0) throw new IllegalArgumentException("label must be positive");
        if (!Double.isFinite(centroidX)
                || !Double.isFinite(centroidY)
                || !Double.isFinite(centroidZ)) {
            throw new IllegalArgumentException("centroid must be finite");
        }
        if (!Double.isFinite(volume) || volume <= 0.0) {
            throw new IllegalArgumentException("volume must be positive and finite");
        }
        this.index = index;
        this.typeIndex = typeIndex;
        this.typeName = typeName;
        this.label = label;
        this.centroidX = centroidX;
        this.centroidY = centroidY;
        this.centroidZ = centroidZ;
        this.volume = volume;
    }

    @Override
    public int getIndex() {
        return index;
    }

    @Override
    public int getTypeIndex() {
        return typeIndex;
    }

    @Override
    public String getTypeName() {
        return typeName;
    }

    @Override
    public long getLabel() {
        return label;
    }

    public double getCentroidX() {
        return centroidX;
    }

    public double getCentroidY() {
        return centroidY;
    }

    public double getCentroidZ() {
        return centroidZ;
    }

    public double getVolume() {
        return volume;
    }
}

