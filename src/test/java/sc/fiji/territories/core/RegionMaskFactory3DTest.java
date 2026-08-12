package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegionMaskFactory3DTest {

    @Test
    public void createsIndependentAndUnionRegionsFromPositiveLabels() {
        ImageStack stack = new ImageStack(3, 2);
        ByteProcessor first = new ByteProcessor(3, 2);
        ByteProcessor second = new ByteProcessor(3, 2);
        first.set(0, 0, 1);
        first.set(1, 0, 1);
        second.set(2, 1, 2);
        stack.addSlice(first);
        stack.addSlice(second);
        ImagePlus mask = new ImagePlus("Brain", stack);

        List<RegionMask3D> independent =
                RegionMaskFactory3D.create(mask, RegionMode.INDEPENDENT);
        List<RegionMask3D> union =
                RegionMaskFactory3D.create(mask, RegionMode.UNION);

        assertEquals(2, independent.size());
        assertEquals(2L, independent.get(0).getVoxelCount());
        assertTrue(independent.get(0).contains(0, 0, 0));
        assertFalse(independent.get(0).contains(2, 1, 1));
        assertEquals(1, union.size());
        assertEquals(3L, union.get(0).getVoxelCount());
        assertTrue(union.get(0).contains(2, 1, 1));
    }
}

