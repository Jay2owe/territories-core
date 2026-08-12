package sc.fiji.territories.core;

import ij.process.FloatProcessor;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DensityEngineTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    public void correctedCountDensityIntegratesToObjectCount() {
        DensityResult result = DensityEngine.generate(
                Arrays.asList(object(0, 50.5, 50.5, 4.0), object(1, 60.5, 50.5, 7.0)),
                rectangle("Field", 0.0, 0.0, 101.0, 101.0),
                "Cells",
                101, 101, 1.0, 1.0, "um", 4.0,
                DensityWeighting.OBJECT_COUNT,
                DensityBoundaryMode.CORRECTED);

        assertEquals(2.0, integrated(result), 1.0e-5);
        assertTrue(result.getLocalDensityByObjectIndex().get(0) > 0.0);
    }

    @Test
    public void areaWeightedDensityIntegratesToSummedArea() {
        DensityResult result = DensityEngine.generate(
                Arrays.asList(object(0, 50.5, 50.5, 4.0), object(1, 60.5, 50.5, 7.0)),
                rectangle("Field", 0.0, 0.0, 101.0, 101.0),
                "Cells",
                101, 101, 1.0, 1.0, "um", 4.0,
                DensityWeighting.OBJECT_SIZE,
                DensityBoundaryMode.CORRECTED);

        assertEquals(11.0, integrated(result), 1.0e-4);
    }

    @Test
    public void correctionRestoresMassLostAtAnEdge() {
        SpatialObject2D edgeObject = object(0, 0.5, 50.5, 1.0);
        SpatialRegion2D region = rectangle("Field", 0.0, 0.0, 101.0, 101.0);
        DensityResult corrected = DensityEngine.generate(
                Collections.singletonList(edgeObject), region, "Cells",
                101, 101, 1.0, 1.0, "um", 5.0,
                DensityWeighting.OBJECT_COUNT, DensityBoundaryMode.CORRECTED);
        DensityResult clipped = DensityEngine.generate(
                Collections.singletonList(edgeObject), region, "Cells",
                101, 101, 1.0, 1.0, "um", 5.0,
                DensityWeighting.OBJECT_COUNT, DensityBoundaryMode.CLIPPED);

        assertEquals(1.0, integrated(corrected), 1.0e-5);
        assertTrue(integrated(clipped) < 0.65);
        assertEquals(0.0, corrected.getLocalDensityByObjectIndex().get(0), 0.0);
    }

    @Test
    public void outsideRegionPixelsAreNaNAndAutoBandwidthIsRecorded() {
        DensityResult result = DensityEngine.generate(
                Collections.singletonList(object(0, 5.5, 5.5, 1.0)),
                rectangle("Small", 3.0, 3.0, 8.0, 8.0),
                "Cells",
                10, 10, 1.0, 1.0, "um", 0.0,
                DensityWeighting.OBJECT_COUNT, DensityBoundaryMode.CORRECTED);

        assertTrue(Float.isNaN(result.getDensityMap().getProcessor().getf(0, 0)));
        assertEquals(3.0, result.getBandwidthMicrons(), 0.0);
    }

    @Test
    public void disconnectedComponentsDoNotShareDensity() {
        SpatialRegion2D disconnected = new SpatialRegion2D(
                "Disconnected",
                rectangle("Left", 0.0, 0.0, 3.0, 3.0).getGeometry().union(
                        rectangle("Right", 6.0, 0.0, 9.0, 3.0).getGeometry()));
        DensityResult result = DensityEngine.generate(
                Collections.singletonList(object(0, 1.5, 1.5, 1.0)),
                disconnected,
                "Cells",
                9, 3, 1.0, 1.0, "um", 10.0,
                DensityWeighting.OBJECT_COUNT,
                DensityBoundaryMode.CORRECTED);

        assertEquals(1.0, integrated(result), 1.0e-5);
        assertEquals(0.0, result.getDensityMap().getProcessor().getf(7, 1), 0.0);
    }

    @Test
    public void disconnectedObjectsHaveZeroLeaveOneOutDensityAcrossGap() {
        SpatialRegion2D disconnected = new SpatialRegion2D(
                "Disconnected",
                rectangle("Left", 0.0, 0.0, 3.0, 3.0).getGeometry().union(
                        rectangle("Right", 6.0, 0.0, 9.0, 3.0).getGeometry()));
        DensityResult result = DensityEngine.generate(
                Arrays.asList(
                        object(0, 1.5, 1.5, 1.0),
                        object(1, 7.5, 1.5, 1.0)),
                disconnected,
                "Cells",
                9, 3, 1.0, 1.0, "um", 10.0,
                DensityWeighting.OBJECT_COUNT,
                DensityBoundaryMode.CORRECTED);

        assertEquals(0.0, result.getLocalDensityByObjectIndex().get(0), 0.0);
        assertEquals(0.0, result.getLocalDensityByObjectIndex().get(1), 0.0);
    }

    @Test
    public void boundaryCentroidContributesToItsCoveredRegion() {
        DensityResult result = DensityEngine.generate(
                Collections.singletonList(object(0, 1.0, 0.5, 1.0)),
                rectangle("One pixel", 0.0, 0.0, 1.0, 1.0),
                "Cells",
                1, 1, 1.0, 1.0, "um", 1.0,
                DensityWeighting.OBJECT_COUNT,
                DensityBoundaryMode.CORRECTED);

        assertEquals(1.0, integrated(result), 1.0e-7);
        assertEquals(0.0, result.getLocalDensityByObjectIndex().get(0), 0.0);
    }

    @Test
    public void rejectsRegionWithNoSampledKernelSupport() {
        try {
            DensityEngine.generate(
                    Collections.singletonList(object(0, 1.0, 1.0, 1.0)),
                    rectangle("Subpixel", 0.9, 0.9, 1.1, 1.1),
                    "Cells",
                    3, 3, 1.0, 1.0, "um", 1.0,
                    DensityWeighting.OBJECT_COUNT,
                    DensityBoundaryMode.CORRECTED);
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage().contains("no sampled support"));
            return;
        }
        throw new AssertionError("Expected an unsupported sampled kernel to fail");
    }

    private static double integrated(DensityResult result) {
        FloatProcessor processor = (FloatProcessor) result.getDensityMap().getProcessor();
        double sum = 0.0;
        for (int y = 0; y < processor.getHeight(); y++) {
            for (int x = 0; x < processor.getWidth(); x++) {
                float value = processor.getf(x, y);
                if (Float.isFinite(value)) sum += value;
            }
        }
        return sum * result.getDensityMap().getCalibration().pixelWidth
                * result.getDensityMap().getCalibration().pixelHeight;
    }

    private static SpatialObject2D object(int index, double x, double y, double area) {
        return new SpatialObject2D(index, 0, "Cells", index + 1L, x, y, area);
    }

    private static SpatialRegion2D rectangle(
            String name, double minX, double minY, double maxX, double maxY) {
        return new SpatialRegion2D(name, GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
                new Coordinate(minX, minY),
                new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY),
                new Coordinate(minX, maxY),
                new Coordinate(minX, minY)
        }));
    }
}
