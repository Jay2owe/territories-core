package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts calibrated centroids and volumes from one 3D label stack. */
public final class LabelObjectExtractor3D {

    private LabelObjectExtractor3D() {
    }

    public static List<SpatialObject3D> extract(
            ImagePlus labels, int typeIndex, int firstObjectIndex) {
        validateStack(labels);
        if (typeIndex < 0 || firstObjectIndex < 0) {
            throw new IllegalArgumentException("indices must be non-negative");
        }
        Calibration calibration = labels.getCalibration();
        double pixelWidth = calibratedSize(calibration.pixelWidth);
        double pixelHeight = calibratedSize(calibration.pixelHeight);
        double pixelDepth = calibratedSize(calibration.pixelDepth);
        ImageStack stack = labels.getStack();
        LinkedHashMap<Long, Accumulator> objects =
                new LinkedHashMap<Long, Accumulator>();

        for (int z = 0; z < stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = 0; y < processor.getHeight(); y++) {
                for (int x = 0; x < processor.getWidth(); x++) {
                    double raw = processor.getf(x, y);
                    if (raw == 0.0) continue;
                    if (!Double.isFinite(raw)
                            || raw < 0.0
                            || raw != Math.rint(raw)
                            || raw > Long.MAX_VALUE) {
                        throw new IllegalArgumentException(
                                "label image '" + labels.getTitle()
                                        + "' contains a non-positive or non-integer label at ("
                                        + x + ", " + y + ", " + z + "): " + raw);
                    }
                    long label = (long) raw;
                    Accumulator accumulator = objects.get(label);
                    if (accumulator == null) {
                        accumulator = new Accumulator();
                        objects.put(label, accumulator);
                    }
                    accumulator.count++;
                    accumulator.sumX += x + 0.5;
                    accumulator.sumY += y + 0.5;
                    accumulator.sumZ += z + 0.5;
                }
            }
        }

        String typeName = labels.getTitle();
        if (typeName == null || typeName.trim().isEmpty()) {
            typeName = "Type_" + (typeIndex + 1);
        }
        ArrayList<SpatialObject3D> result =
                new ArrayList<SpatialObject3D>(objects.size());
        int index = firstObjectIndex;
        for (Map.Entry<Long, Accumulator> entry : objects.entrySet()) {
            Accumulator accumulator = entry.getValue();
            result.add(new SpatialObject3D(
                    index++,
                    typeIndex,
                    typeName,
                    entry.getKey(),
                    accumulator.sumX / accumulator.count * pixelWidth,
                    accumulator.sumY / accumulator.count * pixelHeight,
                    accumulator.sumZ / accumulator.count * pixelDepth,
                    accumulator.count * pixelWidth * pixelHeight * pixelDepth));
        }
        return result;
    }

    private static void validateStack(ImagePlus labels) {
        if (labels == null) throw new IllegalArgumentException("label image must not be null");
        if (labels.getNChannels() != 1 || labels.getNFrames() != 1
                || labels.getStackSize() < 2) {
            throw new IllegalArgumentException(
                    "label image '" + labels.getTitle()
                            + "' must be one 3D stack with at least two z slices");
        }
    }

    private static double calibratedSize(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private static final class Accumulator {
        private long count;
        private double sumX;
        private double sumY;
        private double sumZ;
    }
}
