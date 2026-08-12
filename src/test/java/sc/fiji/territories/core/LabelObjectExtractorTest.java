package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class LabelObjectExtractorTest {

    @Test
    public void extractsCalibratedCentroidsAndAreas() {
        ShortProcessor processor = new ShortProcessor(4, 3);
        processor.set(0, 0, 1);
        processor.set(1, 0, 1);
        processor.set(3, 1, 7);
        processor.set(3, 2, 7);
        ImagePlus labels = new ImagePlus("Microglia", processor);
        Calibration calibration = labels.getCalibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 3.0;
        calibration.setUnit("um");

        List<SpatialObject2D> objects = LabelObjectExtractor.extract(labels, 2, 10);

        assertEquals(2, objects.size());
        assertEquals(10, objects.get(0).getIndex());
        assertEquals(2, objects.get(0).getTypeIndex());
        assertEquals("Microglia", objects.get(0).getTypeName());
        assertEquals(1L, objects.get(0).getLabel());
        assertEquals(2.0, objects.get(0).getCentroidX(), 1.0e-12);
        assertEquals(1.5, objects.get(0).getCentroidY(), 1.0e-12);
        assertEquals(12.0, objects.get(0).getArea(), 1.0e-12);
        assertEquals(7.0, objects.get(1).getCentroidX(), 1.0e-12);
        assertEquals(6.0, objects.get(1).getCentroidY(), 1.0e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsStacksUntilGenuineThreeDimensionalModeExists() {
        ImagePlus stack = ij.IJ.createImage("Labels", "16-bit", 4, 4, 2);
        LabelObjectExtractor.extract(stack, 0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteLabelPixels() {
        FloatProcessor processor = new FloatProcessor(2, 2);
        processor.setf(1, 1, Float.NaN);
        LabelObjectExtractor.extract(new ImagePlus("Invalid", processor), 0, 0);
    }
}
