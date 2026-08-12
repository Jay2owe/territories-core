package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Parses positive integer labels in a 3D mask into independent or union regions. */
public final class RegionMaskFactory3D {

    private RegionMaskFactory3D() {
    }

    public static List<RegionMask3D> create(ImagePlus mask, RegionMode mode) {
        validate(mask, mode);
        int width = mask.getWidth();
        int height = mask.getHeight();
        int depth = mask.getStackSize();
        int[] labels = new int[width * height * depth];
        Set<Integer> unique = new TreeSet<Integer>();
        ImageStack stack = mask.getStack();
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double raw = processor.getf(x, y);
                    if (!Double.isFinite(raw)) {
                        throw invalid(mask, x, y, z, raw);
                    }
                    if (raw == 0.0) continue;
                    if (raw < 0.0 || raw != Math.rint(raw) || raw > Integer.MAX_VALUE) {
                        throw invalid(mask, x, y, z, raw);
                    }
                    int value = (int) raw;
                    labels[(z * height + y) * width + x] = value;
                    unique.add(value);
                }
            }
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("3D region mask contains no positive voxels");
        }

        Calibration calibration = mask.getCalibration();
        double pixelWidth = calibrated(calibration.pixelWidth);
        double pixelHeight = calibrated(calibration.pixelHeight);
        double pixelDepth = calibrated(calibration.pixelDepth);
        String unit = calibration.getUnit();
        String baseName = mask.getTitle() == null || mask.getTitle().trim().isEmpty()
                ? "Region" : mask.getTitle();
        ArrayList<RegionMask3D> result = new ArrayList<RegionMask3D>();
        if (mode == RegionMode.UNION) {
            result.add(new RegionMask3D(
                    baseName + "_Union",
                    width, height, depth,
                    pixelWidth, pixelHeight, pixelDepth, unit,
                    labels, 0, true, count(labels, 0, true)));
            return result;
        }
        boolean oneRegion = unique.size() == 1;
        for (Integer label : unique) {
            result.add(new RegionMask3D(
                    oneRegion ? baseName : baseName + "_Label_" + label,
                    width, height, depth,
                    pixelWidth, pixelHeight, pixelDepth, unit,
                    labels, label, false, count(labels, label, false)));
        }
        return result;
    }

    private static void validate(ImagePlus mask, RegionMode mode) {
        if (mask == null) throw new IllegalArgumentException("3D region mask is required");
        if (mode == null) throw new IllegalArgumentException("region mode is required");
        if (mask.getNChannels() != 1 || mask.getNFrames() != 1
                || mask.getStackSize() < 2) {
            throw new IllegalArgumentException(
                    "3D region mask must be one stack with at least two z slices");
        }
        long voxels = (long) mask.getWidth() * mask.getHeight() * mask.getStackSize();
        if (voxels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("3D region mask is too large for one Java array");
        }
    }

    private static long count(int[] labels, int selected, boolean union) {
        long result = 0;
        for (int value : labels) {
            if (union ? value > 0 : value == selected) result++;
        }
        return result;
    }

    private static double calibrated(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private static IllegalArgumentException invalid(
            ImagePlus mask, int x, int y, int z, double value) {
        return new IllegalArgumentException(
                "region mask '" + mask.getTitle()
                        + "' contains a negative or non-integer value at ("
                        + x + ", " + y + ", " + z + "): " + value);
    }
}

