package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DensityEngine3DTest {

    @Test
    public void correctedCountDensityIntegratesToObjectCount() {
        RegionMask3D region = fullMask(21, 21, 21);
        DensityResult3D result = DensityEngine3D.generate(
                Arrays.asList(
                        object(0, 10.5, 10.5, 10.5, 4.0),
                        object(1, 14.5, 10.5, 10.5, 7.0)),
                region,
                "Cells",
                2.0,
                DensityWeighting.OBJECT_COUNT,
                DensityBoundaryMode.CORRECTED);

        assertEquals(2.0, integrated(result), 1.0e-5);
        assertTrue(result.getLocalDensityByObjectIndex().get(0) > 0.0);
        result.getDensityVolume().close();
    }

    @Test
    public void sizeWeightingIntegratesToObjectVolume() {
        RegionMask3D region = fullMask(21, 21, 21);
        DensityResult3D result = DensityEngine3D.generate(
                Arrays.asList(
                        object(0, 10.5, 10.5, 10.5, 4.0),
                        object(1, 14.5, 10.5, 10.5, 7.0)),
                region,
                "Cells",
                2.0,
                DensityWeighting.OBJECT_SIZE,
                DensityBoundaryMode.CORRECTED);

        assertEquals(11.0, integrated(result), 1.0e-4);
        result.getDensityVolume().close();
    }

    @Test
    public void edgeCorrectionPreservesMassAndLocalDensityLeavesSelfOut() {
        RegionMask3D region = fullMask(15, 15, 15);
        DensityResult3D corrected = DensityEngine3D.generate(
                Collections.singletonList(object(0, 0.5, 7.5, 7.5, 1.0)),
                region,
                "Cells",
                2.0,
                DensityWeighting.OBJECT_COUNT,
                DensityBoundaryMode.CORRECTED);
        DensityResult3D clipped = DensityEngine3D.generate(
                Collections.singletonList(object(0, 0.5, 7.5, 7.5, 1.0)),
                region,
                "Cells",
                2.0,
                DensityWeighting.OBJECT_COUNT,
                DensityBoundaryMode.CLIPPED);

        assertEquals(1.0, integrated(corrected), 1.0e-5);
        assertTrue(integrated(clipped) < 0.7);
        assertEquals(0.0, corrected.getLocalDensityByObjectIndex().get(0), 0.0);
        corrected.getDensityVolume().close();
        clipped.getDensityVolume().close();
    }

    @Test
    public void disconnectedComponentsDoNotShareDensity() {
        RegionMask3D region = disconnectedMask();
        DensityResult3D result = DensityEngine3D.generate(
                Collections.singletonList(object(0, 1.5, 1.5, 1.5, 1.0)),
                region,
                "Cells",
                10.0,
                DensityWeighting.OBJECT_COUNT,
                DensityBoundaryMode.CORRECTED);

        assertEquals(1.0, integrated(result), 1.0e-5);
        assertEquals(0.0, result.getDensityVolume().getStack().getVoxel(7, 1, 1), 0.0);
        result.getDensityVolume().close();
    }

    @Test
    public void rejectsBandwidthWithNoSampledVoxelSupport() {
        try {
            DensityEngine3D.generate(
                    Collections.singletonList(object(0, 1.0, 1.0, 1.0, 1.0)),
                    fullMask(3, 3, 2),
                    "Cells",
                    1.0e-12,
                    DensityWeighting.OBJECT_COUNT,
                    DensityBoundaryMode.CLIPPED);
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("no sampled support"));
            return;
        }
        throw new AssertionError("Expected an unsupported sampled kernel to fail");
    }

    private static double integrated(DensityResult3D result) {
        ImagePlus image = result.getDensityVolume();
        double sum = 0.0;
        for (int z = 1; z <= image.getStackSize(); z++) {
            ImageProcessor processor = image.getStack().getProcessor(z);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    float value = processor.getf(x, y);
                    if (Float.isFinite(value)) sum += value;
                }
            }
        }
        return sum
                * image.getCalibration().pixelWidth
                * image.getCalibration().pixelHeight
                * image.getCalibration().pixelDepth;
    }

    private static SpatialObject3D object(
            int index, double x, double y, double z, double volume) {
        return new SpatialObject3D(
                index, 0, "Cells", index + 1L, x, y, z, volume);
    }

    private static RegionMask3D fullMask(int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            processor.setValue(1);
            processor.fill();
            stack.addSlice(processor);
        }
        return RegionMaskFactory3D.create(
                new ImagePlus("Region", stack), RegionMode.INDEPENDENT).get(0);
    }

    private static RegionMask3D disconnectedMask() {
        int width = 9;
        int height = 3;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < 3; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (x <= 2 || x >= 6) processor.set(x, y, 1);
                }
            }
            stack.addSlice(processor);
        }
        return RegionMaskFactory3D.create(
                new ImagePlus("Disconnected", stack), RegionMode.INDEPENDENT).get(0);
    }
}
