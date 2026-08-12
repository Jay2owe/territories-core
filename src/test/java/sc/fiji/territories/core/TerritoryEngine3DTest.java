package sc.fiji.territories.core;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerritoryEngine3DTest {

    @Test
    public void regularThreeCubedLatticeHasKnownCentralTerritory() {
        RegionMask3D region = fullMask(9, 9, 9);
        List<SpatialObject3D> objects = new ArrayList<SpatialObject3D>();
        int index = 0;
        for (int z = 0; z < 3; z++) {
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 3; x++) {
                    objects.add(object(
                            index,
                            x * 3.0 + 1.5,
                            y * 3.0 + 1.5,
                            z * 3.0 + 1.5));
                    index++;
                }
            }
        }

        TerritoryResult3D result = TerritoryEngine3D.analyze(
                objects, region, EdgeCellPolicy.INCLUDE_FLAGGED);

        assertEquals(27, result.getCells().size());
        TerritoryCell3D centre = result.getCells().get(13);
        assertEquals(27L, centre.getVoxelCount());
        assertEquals(27.0, centre.getVolume(), 0.0);
        assertEquals(6, centre.getNeighborObjectIndices().size());
        assertFalse(centre.isEdgeCell());
        assertTrue(result.getCells().get(0).isEdgeCell());
        assertEquals(0.0,
                result.getRegularity().getTerritorySizeCoefficientOfVariation(), 0.0);
        assertEquals(3.0, result.getRegularity().getNearestNeighborMean(), 0.0);
        result.getTerritoryLabels().close();
    }

    @Test
    public void twoObjectsSplitABoxAndShareOneFaceNeighbourship() {
        RegionMask3D region = fullMask(10, 4, 4);
        List<SpatialObject3D> objects = new ArrayList<SpatialObject3D>();
        objects.add(object(0, 2.5, 1.5, 1.5));
        objects.add(object(1, 7.5, 1.5, 1.5));

        TerritoryResult3D result = TerritoryEngine3D.analyze(
                objects, region, EdgeCellPolicy.INCLUDE_FLAGGED);

        assertEquals(80L, result.getCells().get(0).getVoxelCount());
        assertEquals(80L, result.getCells().get(1).getVoxelCount());
        assertEquals(1, result.getCells().get(0).getNeighborObjectIndices().size());
        assertEquals(Integer.valueOf(1),
                result.getCells().get(0).getNeighborObjectIndices().get(0));
        result.getTerritoryLabels().close();
    }

    @Test
    public void anisotropicVoxelSpacingControlsNearestTerritory() {
        ImageStack stack = new ImageStack(3, 1);
        for (int z = 0; z < 3; z++) {
            ByteProcessor processor = new ByteProcessor(3, 1);
            processor.setValue(1);
            processor.fill();
            stack.addSlice(processor);
        }
        ImagePlus mask = new ImagePlus("Region", stack);
        mask.getCalibration().pixelDepth = 5.0;
        RegionMask3D region =
                RegionMaskFactory3D.create(mask, RegionMode.INDEPENDENT).get(0);
        List<SpatialObject3D> objects = new ArrayList<SpatialObject3D>();
        objects.add(object(0, 0.5, 0.5, 12.5));
        objects.add(object(1, 2.5, 0.5, 2.5));

        TerritoryResult3D result = TerritoryEngine3D.analyze(
                objects, region, EdgeCellPolicy.INCLUDE_FLAGGED);

        assertEquals(
                2.0,
                result.getTerritoryLabels().getStack().getProcessor(1).getf(0, 0),
                0.0);
        result.getTerritoryLabels().close();
    }

    /**
     * Only the lattice centre survives edge exclusion. Its nearest neighbour is
     * an excluded cell, so the distance must still be measured, while the
     * spread of a single sample stays undefined.
     */
    @Test
    public void singleIncludedCellMeasuresAgainstExcludedNeighbours() {
        RegionMask3D region = fullMask(9, 9, 9);
        List<SpatialObject3D> objects = new ArrayList<SpatialObject3D>();
        int index = 0;
        for (int z = 0; z < 3; z++) {
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 3; x++) {
                    objects.add(object(
                            index++, x * 3.0 + 1.5, y * 3.0 + 1.5, z * 3.0 + 1.5));
                }
            }
        }

        TerritoryResult3D result = TerritoryEngine3D.analyze(
                objects, region, EdgeCellPolicy.EXCLUDE_FROM_SUMMARIES);

        RegularityResult regularity = result.getRegularity();
        assertEquals(1, regularity.getIncludedObjects());
        assertEquals(3.0, regularity.getNearestNeighborMean(), 1.0e-8);
        assertTrue(Double.isNaN(regularity.getNearestNeighborStandardDeviation()));
        assertTrue(Double.isNaN(regularity.getNearestNeighborRegularityRatio()));
        assertTrue(Double.isNaN(regularity.getTerritorySizeCoefficientOfVariation()));
        result.getTerritoryLabels().close();
    }

    @Test
    public void disconnectedMaskComponentWithoutObjectsStaysUnassigned() {
        ImageStack stack = new ImageStack(7, 1);
        for (int z = 0; z < 2; z++) {
            ByteProcessor processor = new ByteProcessor(7, 1);
            processor.set(0, 0, 1);
            processor.set(1, 0, 1);
            processor.set(5, 0, 1);
            processor.set(6, 0, 1);
            stack.addSlice(processor);
        }
        RegionMask3D region = RegionMaskFactory3D.create(
                new ImagePlus("Islands", stack), RegionMode.UNION).get(0);
        List<SpatialObject3D> objects = new ArrayList<SpatialObject3D>();
        objects.add(object(0, 0.5, 0.5, 0.5));

        TerritoryResult3D result = TerritoryEngine3D.analyze(
                objects, region, EdgeCellPolicy.INCLUDE_FLAGGED);

        assertEquals(4L, result.getCells().get(0).getVoxelCount());
        assertEquals(
                0.0,
                result.getTerritoryLabels().getStack().getProcessor(1).getf(5, 0),
                0.0);
        result.getTerritoryLabels().close();
    }

    private static SpatialObject3D object(int index, double x, double y, double z) {
        return new SpatialObject3D(
                index, 0, "Cells", index + 1L, x, y, z, 1.0);
    }

    private static RegionMask3D fullMask(int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            processor.setValue(1);
            processor.fill();
            stack.addSlice(processor);
        }
        ImagePlus mask = new ImagePlus("Region", stack);
        return RegionMaskFactory3D.create(mask, RegionMode.INDEPENDENT).get(0);
    }
}
