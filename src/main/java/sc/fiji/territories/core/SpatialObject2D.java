package sc.fiji.territories.core;

/** Immutable calibrated object measurement extracted from a label image. */
public final class SpatialObject2D implements TypedSpatialObject {

    private final int index;
    private final int typeIndex;
    private final String typeName;
    private final long label;
    private final double centroidX;
    private final double centroidY;
    private final double area;

    public SpatialObject2D(
            int index,
            int typeIndex,
            String typeName,
            long label,
            double centroidX,
            double centroidY,
            double area) {
        if (index < 0) throw new IllegalArgumentException("index must be non-negative");
        if (typeIndex < 0) throw new IllegalArgumentException("typeIndex must be non-negative");
        if (typeName == null || typeName.trim().isEmpty()) {
            throw new IllegalArgumentException("typeName must not be empty");
        }
        if (label <= 0) throw new IllegalArgumentException("label must be positive");
        if (!Double.isFinite(centroidX) || !Double.isFinite(centroidY)) {
            throw new IllegalArgumentException("centroid must be finite");
        }
        if (!Double.isFinite(area) || area <= 0.0) {
            throw new IllegalArgumentException("area must be positive and finite");
        }
        this.index = index;
        this.typeIndex = typeIndex;
        this.typeName = typeName;
        this.label = label;
        this.centroidX = centroidX;
        this.centroidY = centroidY;
        this.area = area;
    }

    public int getIndex() {
        return index;
    }

    public int getTypeIndex() {
        return typeIndex;
    }

    public String getTypeName() {
        return typeName;
    }

    public long getLabel() {
        return label;
    }

    public double getCentroidX() {
        return centroidX;
    }

    public double getCentroidY() {
        return centroidY;
    }

    public double getArea() {
        return area;
    }
}
