package sc.fiji.territories.core;

/** One named 3D region backed by a shared integer mask volume. */
public final class RegionMask3D {

    private final String name;
    private final int width;
    private final int height;
    private final int depth;
    private final double pixelWidth;
    private final double pixelHeight;
    private final double pixelDepth;
    private final String spatialUnit;
    private final int[] labels;
    private final int selectedLabel;
    private final boolean union;
    private final long voxelCount;

    RegionMask3D(
            String name,
            int width,
            int height,
            int depth,
            double pixelWidth,
            double pixelHeight,
            double pixelDepth,
            String spatialUnit,
            int[] labels,
            int selectedLabel,
            boolean union,
            long voxelCount) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.pixelDepth = pixelDepth;
        this.spatialUnit = spatialUnit;
        this.labels = labels;
        this.selectedLabel = selectedLabel;
        this.union = union;
        this.voxelCount = voxelCount;
    }

    public String getName() {
        return name;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getDepth() {
        return depth;
    }

    public double getPixelWidth() {
        return pixelWidth;
    }

    public double getPixelHeight() {
        return pixelHeight;
    }

    public double getPixelDepth() {
        return pixelDepth;
    }

    public String getSpatialUnit() {
        return spatialUnit;
    }

    public long getVoxelCount() {
        return voxelCount;
    }

    public boolean contains(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) {
            return false;
        }
        return containsIndex((z * height + y) * width + x);
    }

    public boolean containsIndex(int index) {
        int value = labels[index];
        return union ? value > 0 : value == selectedLabel;
    }
}

