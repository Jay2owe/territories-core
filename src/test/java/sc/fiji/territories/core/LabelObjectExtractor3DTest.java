package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class LabelObjectExtractor3DTest {

    @Test
    public void extractsCalibratedCentroidAndVolume() {
        ImageStack stack = new ImageStack(3, 3);
        ShortProcessor first = new ShortProcessor(3, 3);
        ShortProcessor second = new ShortProcessor(3, 3);
        first.set(0, 0, 4);
        second.set(2, 2, 4);
        stack.addSlice(first);
        stack.addSlice(second);
        ImagePlus labels = new ImagePlus("Microglia", stack);
        Calibration calibration = labels.getCalibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 3.0;
        calibration.pixelDepth = 4.0;
        calibration.setUnit("um");

        List<SpatialObject3D> objects = LabelObjectExtractor3D.extract(labels, 1, 7);

        assertEquals(1, objects.size());
        SpatialObject3D object = objects.get(0);
        assertEquals(7, object.getIndex());
        assertEquals(3.0, object.getCentroidX(), 1.0e-12);
        assertEquals(4.5, object.getCentroidY(), 1.0e-12);
        assertEquals(4.0, object.getCentroidZ(), 1.0e-12);
        assertEquals(48.0, object.getVolume(), 1.0e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteLabelVoxels() {
        ImageStack stack = new ImageStack(2, 2);
        FloatProcessor first = new FloatProcessor(2, 2);
        first.setf(1, 1, Float.POSITIVE_INFINITY);
        stack.addSlice(first);
        stack.addSlice(new FloatProcessor(2, 2));

        LabelObjectExtractor3D.extract(new ImagePlus("Invalid", stack), 0, 0);
    }
}
