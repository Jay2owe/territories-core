package sc.fiji.territories.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * {@link LabelObjectExtractor3D} and {@link RegionMaskFactory3D}, which now
 * keep the last label at hand, against their 0.2.1 forms kept as
 * {@link ReferenceLabelObjectExtractor3D} and
 * {@link ReferenceRegionMaskFactory3D}: same objects in the same order with
 * the same raw bits, same regions voxel for voxel, same rejection messages.
 */
public class ExtractorAndMaskEquivalenceTest {

    @Test
    public void extractionAndMasksMatchTheReference() {
        Random random = new Random(20260930L);
        int rejected = 0;
        int objects = 0;
        for (int trial = 0; trial < 200; trial++) {
            int type = random.nextInt(3);
            ImagePlus image = stack(random, type, 1 + random.nextInt(40),
                    1 + random.nextInt(30), 2 + random.nextInt(4), trial % 5 == 4);
            if (trial % 3 == 1) {
                image.getCalibration().pixelWidth = 0.21;
                image.getCalibration().pixelHeight = 0.19;
                image.getCalibration().pixelDepth = 1.3;
                image.getCalibration().setUnit("micron");
            }
            String expected = objects(true, image, trial);
            String actual = objects(false, image, trial);
            assertEquals("trial " + trial, expected, actual);
            if (expected.startsWith("rejected")) rejected++;
            else objects += expected.split("\n").length;
            for (RegionMode mode : RegionMode.values()) {
                assertEquals("trial " + trial + " " + mode,
                        regions(true, image, mode), regions(false, image, mode));
            }
        }
        assertTrue(rejected > 10);
        assertTrue(objects > 2000);
    }

    private static String objects(boolean reference, ImagePlus image, int trial) {
        try {
            List<SpatialObject3D> list = reference
                    ? ReferenceLabelObjectExtractor3D.extract(image, trial % 3, trial)
                    : LabelObjectExtractor3D.extract(image, trial % 3, trial);
            StringBuilder out = new StringBuilder();
            for (SpatialObject3D o : list) {
                out.append(o.getIndex()).append(' ').append(o.getTypeIndex()).append(' ')
                        .append(o.getTypeName()).append(' ').append(o.getLabel()).append(' ')
                        .append(Double.doubleToRawLongBits(o.getCentroidX())).append(' ')
                        .append(Double.doubleToRawLongBits(o.getCentroidY())).append(' ')
                        .append(Double.doubleToRawLongBits(o.getCentroidZ())).append(' ')
                        .append(Double.doubleToRawLongBits(o.getVolume())).append('\n');
            }
            return out.toString();
        } catch (IllegalArgumentException rejected) {
            return "rejected " + rejected.getMessage();
        }
    }

    private static String regions(boolean reference, ImagePlus image, RegionMode mode) {
        try {
            List<RegionMask3D> list = reference
                    ? ReferenceRegionMaskFactory3D.create(image, mode)
                    : RegionMaskFactory3D.create(image, mode);
            StringBuilder out = new StringBuilder();
            for (RegionMask3D r : list) {
                out.append(r.getName()).append(' ').append(r.getWidth()).append('x')
                        .append(r.getHeight()).append('x').append(r.getDepth()).append(' ')
                        .append(Double.doubleToRawLongBits(r.getPixelWidth())).append(' ')
                        .append(Double.doubleToRawLongBits(r.getPixelHeight())).append(' ')
                        .append(Double.doubleToRawLongBits(r.getPixelDepth())).append(' ')
                        .append(r.getSpatialUnit()).append(' ').append(r.getVoxelCount()).append(' ');
                int total = r.getWidth() * r.getHeight() * r.getDepth();
                for (int i = 0; i < total; i++) out.append(r.containsIndex(i) ? '1' : '0');
                out.append('\n');
            }
            return out.toString();
        } catch (IllegalArgumentException rejected) {
            return "rejected " + rejected.getMessage();
        }
    }

    /** Runs of labels (first appearance not in label order), and optionally bad values. */
    private static ImagePlus stack(Random random, int type, int width, int height, int depth,
                                   boolean bad) {
        ImageStack stack = new ImageStack(width, height);
        int max = type == 0 ? 255 : type == 1 ? 65535 : 900000;
        int label = 0;
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = type == 0 ? new ByteProcessor(width, height)
                    : type == 1 ? new ShortProcessor(width, height)
                    : new FloatProcessor(width, height);
            for (int i = 0; i < width * height; i++) {
                if (random.nextInt(6) == 0) {
                    label = random.nextInt(3) == 0 ? 0 : 1 + random.nextInt(random.nextBoolean() ? 7 : max);
                }
                float value = label;
                if (bad && type == 2 && random.nextInt(300) == 0) {
                    float[] odd = {1.5f, -2f, Float.NaN, Float.POSITIVE_INFINITY, 3.0e9f};
                    value = odd[random.nextInt(odd.length)];
                }
                processor.setf(i, value);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("stack", stack);
    }
}
