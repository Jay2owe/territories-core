package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts calibrated centroids and areas from a two-dimensional label image. */
public final class LabelObjectExtractor {

    private LabelObjectExtractor() {
    }

    public static List<SpatialObject2D> extract(
            ImagePlus labels, int typeIndex, int firstObjectIndex) {
        if (labels == null) throw new IllegalArgumentException("label image must not be null");
        if (labels.getStackSize() != 1) {
            throw new IllegalArgumentException(
                    "label image '" + labels.getTitle() + "' must contain exactly one 2D plane");
        }
        if (typeIndex < 0) throw new IllegalArgumentException("typeIndex must be non-negative");
        if (firstObjectIndex < 0) {
            throw new IllegalArgumentException("firstObjectIndex must be non-negative");
        }

        Calibration calibration = labels.getCalibration();
        double pixelWidth = calibratedSize(calibration.pixelWidth);
        double pixelHeight = calibratedSize(calibration.pixelHeight);
        ImageProcessor processor = labels.getProcessor();
        LinkedHashMap<Long, Accumulator> objects = new LinkedHashMap<Long, Accumulator>();

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
                                    + x + ", " + y + "): " + raw);
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
            }
        }

        String typeName = labels.getTitle();
        if (typeName == null || typeName.trim().isEmpty()) typeName = "Type_" + (typeIndex + 1);
        ArrayList<SpatialObject2D> result = new ArrayList<SpatialObject2D>(objects.size());
        int index = firstObjectIndex;
        for (Map.Entry<Long, Accumulator> entry : objects.entrySet()) {
            Accumulator accumulator = entry.getValue();
            result.add(new SpatialObject2D(
                    index++,
                    typeIndex,
                    typeName,
                    entry.getKey(),
                    accumulator.sumX / accumulator.count * pixelWidth,
                    accumulator.sumY / accumulator.count * pixelHeight,
                    accumulator.count * pixelWidth * pixelHeight));
        }
        return result;
    }

    private static double calibratedSize(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private static final class Accumulator {
        private long count;
        private double sumX;
        private double sumY;
    }
}
