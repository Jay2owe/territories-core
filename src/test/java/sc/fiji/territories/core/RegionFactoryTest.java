package sc.fiji.territories.core;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RegionFactoryTest {

    @Test
    public void createsIndependentCalibratedRegions() {
        Roi first = new Roi(0, 0, 10, 5);
        first.setName("SCN");
        Roi second = new Roi(10, 0, 5, 5);
        second.setName("LH");

        List<SpatialRegion2D> regions = RegionFactory.create(
                Arrays.asList(first, second), RegionMode.INDEPENDENT, 2.0, 3.0);

        assertEquals(2, regions.size());
        assertEquals("SCN", regions.get(0).getName());
        assertEquals(300.0, regions.get(0).getGeometry().getArea(), 1.0e-9);
        assertEquals(0.0, regions.get(0).getGeometry().getEnvelopeInternal().getMinY(), 0.0);
        assertEquals(15.0, regions.get(0).getGeometry().getEnvelopeInternal().getMaxY(), 0.0);
        assertTrue(regions.get(0).getGeometry().covers(
                new org.locationtech.jts.geom.GeometryFactory().createPoint(
                        new org.locationtech.jts.geom.Coordinate(1.0, 1.5))));
    }

    @Test
    public void unionsTouchingRegions() {
        Roi first = new Roi(0, 0, 10, 5);
        Roi second = new Roi(10, 0, 5, 5);

        List<SpatialRegion2D> regions = RegionFactory.create(
                Arrays.asList(first, second), RegionMode.UNION, 1.0, 1.0);

        assertEquals(1, regions.size());
        assertEquals("All_Regions", regions.get(0).getName());
        assertEquals(75.0, regions.get(0).getGeometry().getArea(), 1.0e-9);
    }

    /**
     * A region reaching past the image must be clipped, otherwise territory
     * area is reported over pixels that were never imaged while the density
     * raster silently stops at the image edge.
     */
    @Test
    public void clipsRegionsToTheCalibratedImageRectangle() {
        Roi roi = new Roi(-20, -20, 100, 100);

        SpatialRegion2D region = RegionFactory.create(
                Arrays.asList(roi), RegionMode.INDEPENDENT, 1.0, 1.0, 32, 32).get(0);

        assertEquals(32.0 * 32.0, region.getGeometry().getArea(), 1.0e-9);
        assertEquals(0.0, region.getGeometry().getEnvelopeInternal().getMinX(), 1.0e-9);
        assertEquals(32.0, region.getGeometry().getEnvelopeInternal().getMaxX(), 1.0e-9);
        assertEquals(0.0, region.getGeometry().getEnvelopeInternal().getMinY(), 1.0e-9);
        assertEquals(32.0, region.getGeometry().getEnvelopeInternal().getMaxY(), 1.0e-9);
    }

    /**
     * Where a region's outline runs along the image edge, the overlay returns
     * that edge as a dangling line beside the polygon. The mixed collection has
     * area but is rejected by every downstream covers() call, so the clip must
     * discard the non-polygonal parts.
     */
    @Test
    public void clippingDiscardsLinealCollapses() {
        int[] xs = {4, 20, 20, 40, 40, 32, 32, 4};
        int[] ys = {4, 4, 12, 12, 20, 20, 12, 12};
        Roi roi = new PolygonRoi(xs, ys, xs.length, Roi.POLYGON);
        roi.setName("Staircase");

        SpatialRegion2D region = RegionFactory.create(
                Arrays.asList(roi), RegionMode.INDEPENDENT, 1.0, 1.0, 32, 32).get(0);

        assertTrue(
                region.getGeometry().getClass().getName(),
                region.getGeometry() instanceof org.locationtech.jts.geom.Polygonal);
        assertEquals(128.0, region.getGeometry().getArea(), 1.0e-9);
        // A heterogeneous collection would throw here instead.
        assertTrue(region.getGeometry().covers(
                new org.locationtech.jts.geom.GeometryFactory().createPoint(
                        new org.locationtech.jts.geom.Coordinate(10.0, 8.0))));
    }

    /**
     * "Create Selection" over a thresholded tissue mask yields one composite
     * ROI holding every island. All of them must survive conversion.
     */
    @Test
    public void compositeRoiKeepsEveryIsland() {
        ShapeRoi combined = new ShapeRoi(new Roi(2, 2, 10, 10))
                .or(new ShapeRoi(new Roi(22, 2, 10, 10)));
        combined.setName("Islands");

        SpatialRegion2D region = RegionFactory.create(
                Arrays.asList((Roi) combined), RegionMode.INDEPENDENT, 1.0, 1.0, 40, 20).get(0);

        assertEquals(200.0, region.getGeometry().getArea(), 1.0e-9);
        assertEquals(2, region.getGeometry().getNumGeometries());
    }

    /** An excluded ventricle or vessel is a hole, not a reason to reject. */
    @Test
    public void roiWithAHoleKeepsTheHole() {
        ShapeRoi holed = new ShapeRoi(new Roi(0, 0, 45, 45))
                .not(new ShapeRoi(new Roi(10, 10, 10, 10)));
        holed.setName("Holed");

        SpatialRegion2D region = RegionFactory.create(
                Arrays.asList((Roi) holed), RegionMode.INDEPENDENT, 1.0, 1.0, 60, 60).get(0);

        assertEquals(45.0 * 45.0 - 100.0, region.getGeometry().getArea(), 1.0e-9);
    }

    /**
     * A thresholded mask can carry thousands of islands, and combining them one
     * at a time is quadratic. Compare growth rather than wall-clock: with 4.2x
     * the rings, folding cost roughly 15x the time while pairing costs about
     * the same. A ratio survives a slow or loaded machine, where an absolute
     * ceiling silently stops guarding anything on a fast one.
     */
    @Test(timeout = 120000)
    public void manyIslandCompositeScalesSubQuadratically() {
        // Warm-up is charged to the denominator, which can only push the ratio
        // down, so the threshold sits well above the measured pairing cost
        // (~5-7x) and well below the measured folding cost (~14x).
        long smallNanos = convertSpeckledMask(64);
        long largeNanos = convertSpeckledMask(128);

        double growth = largeNanos / (double) smallNanos;
        assertTrue(
                "441 islands took " + smallNanos / 1000000L + " ms but 1849 took "
                        + largeNanos / 1000000L + " ms (" + growth + "x)",
                growth < 10.0);
    }

    /** Converts a lattice-speckled mask of the given size, returning nanos. */
    private static long convertSpeckledMask(int size) {
        ByteProcessor mask = new ByteProcessor(size, size);
        int islands = 0;
        for (int y = 1; y < size; y += 3) {
            for (int x = 1; x < size; x += 3) {
                mask.set(x, y, 255);
                islands++;
            }
        }
        mask.setThreshold(128, 255, ij.process.ImageProcessor.NO_LUT_UPDATE);
        Roi roi = new ij.plugin.filter.ThresholdToSelection().convert(mask);

        long start = System.nanoTime();
        SpatialRegion2D region = RegionFactory.create(
                Arrays.asList(roi), RegionMode.INDEPENDENT, 1.0, 1.0, size, size).get(0);
        long elapsedNanos = System.nanoTime() - start;

        assertEquals((double) islands, region.getGeometry().getArea(), 1.0e-9);
        assertEquals(islands, region.getGeometry().getNumGeometries());
        return elapsedNanos;
    }

    @Test
    public void compositeRoiSurvivesAnisotropicCalibration() {
        ShapeRoi combined = new ShapeRoi(new Roi(0, 0, 4, 4))
                .or(new ShapeRoi(new Roi(8, 0, 4, 4)));

        SpatialRegion2D region = RegionFactory.create(
                Arrays.asList((Roi) combined), RegionMode.INDEPENDENT, 0.5, 3.0, 20, 10).get(0);

        // Two 4x4 px islands at 0.5 x 3.0 um = 2 x (2.0 x 12.0).
        assertEquals(48.0, region.getGeometry().getArea(), 1.0e-9);
    }

    @Test
    public void clippingRespectsAnisotropicCalibration() {
        Roi roi = new Roi(0, 0, 100, 100);

        SpatialRegion2D region = RegionFactory.create(
                Arrays.asList(roi), RegionMode.INDEPENDENT, 0.5, 2.0, 10, 4).get(0);

        assertEquals(5.0 * 8.0, region.getGeometry().getArea(), 1.0e-9);
    }

    @Test
    public void rejectsRegionEntirelyOutsideTheImage() {
        Roi roi = new Roi(200, 200, 10, 10);
        roi.setName("Offside");
        try {
            RegionFactory.create(
                    Arrays.asList(roi), RegionMode.INDEPENDENT, 1.0, 1.0, 32, 32);
            fail("expected an out-of-image region to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                    expected.getMessage(),
                    expected.getMessage().contains("Offside")
                            && expected.getMessage().contains("entirely outside"));
        }
    }

    /** A ROI sharing only an edge with the image encloses no pixels. */
    @Test
    public void reportsBorderTouchingRegionsAsEnclosingNoPixels() {
        Roi roi = new Roi(32, 0, 10, 10);
        roi.setName("Abutting");
        try {
            RegionFactory.create(
                    Arrays.asList(roi), RegionMode.INDEPENDENT, 1.0, 1.0, 32, 32);
            fail("expected a border-touching region to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                    expected.getMessage(),
                    expected.getMessage().contains("only along its border"));
        }
    }

    /**
     * Clipping distributes over the union, so one off-image ROI must not
     * abort an analysis whose union is perfectly valid.
     */
    @Test
    public void unionDropsRegionsThatDoNotOverlapTheImage() {
        Roi inside = new Roi(0, 0, 10, 10);
        Roi outside = new Roi(200, 200, 10, 10);

        List<SpatialRegion2D> regions = RegionFactory.create(
                Arrays.asList(inside, outside), RegionMode.UNION, 1.0, 1.0, 32, 32);

        assertEquals(1, regions.size());
        assertEquals(100.0, regions.get(0).getGeometry().getArea(), 1.0e-9);
    }

    @Test
    public void unionClipsRegionsThatOverhangTheImage() {
        Roi first = new Roi(-10, 0, 20, 10);
        Roi second = new Roi(20, 0, 20, 10);

        List<SpatialRegion2D> regions = RegionFactory.create(
                Arrays.asList(first, second), RegionMode.UNION, 1.0, 1.0, 32, 32);

        assertEquals(1, regions.size());
        // 10x10 kept from the first ROI, 12x10 from the second.
        assertEquals(220.0, regions.get(0).getGeometry().getArea(), 1.0e-9);
    }

    @Test
    public void unionRejectsWhenNoRegionOverlapsTheImage() {
        try {
            RegionFactory.create(
                    Arrays.asList(new Roi(200, 200, 10, 10), new Roi(300, 300, 10, 10)),
                    RegionMode.UNION, 1.0, 1.0, 32, 32);
            fail("expected a union with no imaged area to be rejected");
        } catch (IllegalArgumentException expected) {
            // Every ROI was dropped, which is worth saying plainly rather than
            // letting the empty-union message stand in for it.
            assertTrue(
                    expected.getMessage(),
                    expected.getMessage().contains("no region ROI overlaps the image"));
        }
    }

    @Test
    public void preservesRoiPositionAfterShapeConversion() {
        Roi roi = new Roi(2, 3, 4, 5);

        SpatialRegion2D region = RegionFactory.create(
                Arrays.asList(roi), RegionMode.INDEPENDENT, 2.0, 3.0).get(0);

        assertEquals(4.0, region.getGeometry().getEnvelopeInternal().getMinX(), 0.0);
        assertEquals(12.0, region.getGeometry().getEnvelopeInternal().getMaxX(), 0.0);
        assertEquals(9.0, region.getGeometry().getEnvelopeInternal().getMinY(), 0.0);
        assertEquals(24.0, region.getGeometry().getEnvelopeInternal().getMaxY(), 0.0);
    }
}
