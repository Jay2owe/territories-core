package sc.fiji.territories.core;

import ij.gui.Roi;
import ij.gui.ShapeRoi;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.operation.union.UnaryUnionOp;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/** Converts ImageJ ROIs into valid calibrated JTS analysis regions. */
public final class RegionFactory {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    /** Maximum deviation, in pixels, when flattening curved ROI segments. */
    private static final double FLATNESS = 0.25;

    private RegionFactory() {
    }

    /**
     * Converts ROIs without clipping them to any image. Callers analysing an
     * image must use the clipping overload instead, so that regions never claim
     * area outside the imaged field.
     */
    static List<SpatialRegion2D> create(
            List<Roi> rois, RegionMode mode, double pixelWidth, double pixelHeight) {
        return create(rois, mode, pixelWidth, pixelHeight, null);
    }

    /**
     * Builds regions clipped to the calibrated image rectangle.
     *
     * <p>ROIs may extend beyond the image; territory tessellation would then
     * report area covering no imaged data while density rasterisation silently
     * truncates at the image edge. Clipping keeps both engines consistent.
     *
     * @param imageWidth  image width in pixels
     * @param imageHeight image height in pixels
     */
    public static List<SpatialRegion2D> create(
            List<Roi> rois, RegionMode mode, double pixelWidth, double pixelHeight,
            int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        if (!Double.isFinite(pixelWidth) || pixelWidth <= 0.0
                || !Double.isFinite(pixelHeight) || pixelHeight <= 0.0) {
            throw new IllegalArgumentException("pixel calibration must be positive and finite");
        }
        Geometry bounds = GEOMETRY_FACTORY.toGeometry(new Envelope(
                0.0, imageWidth * pixelWidth, 0.0, imageHeight * pixelHeight));
        return create(rois, mode, pixelWidth, pixelHeight, bounds);
    }

    private static List<SpatialRegion2D> create(
            List<Roi> rois, RegionMode mode, double pixelWidth, double pixelHeight,
            Geometry imageBounds) {
        if (rois == null || rois.isEmpty()) {
            throw new IllegalArgumentException("at least one region ROI is required");
        }
        if (mode == null) throw new IllegalArgumentException("region mode must not be null");
        if (!Double.isFinite(pixelWidth) || pixelWidth <= 0.0
                || !Double.isFinite(pixelHeight) || pixelHeight <= 0.0) {
            throw new IllegalArgumentException("pixel calibration must be positive and finite");
        }

        ArrayList<SpatialRegion2D> independent = new ArrayList<SpatialRegion2D>(rois.size());
        ArrayList<Geometry> geometries = new ArrayList<Geometry>(rois.size());
        for (int i = 0; i < rois.size(); i++) {
            Roi roi = rois.get(i);
            if (roi == null) throw new IllegalArgumentException("region ROI " + (i + 1) + " is null");
            Geometry geometry = toGeometry(roi, pixelWidth, pixelHeight);
            if (imageBounds != null) {
                Geometry clipped = clip(geometry, imageBounds);
                if (clipped == null) {
                    // Unioned regions merge into one field, so a ROI that
                    // contributes no imaged area simply drops out. An
                    // independent region was asked for by name and cannot.
                    if (mode == RegionMode.UNION) continue;
                    throw new IllegalArgumentException(
                            "region ROI '" + label(roi.getName(), i) + "' "
                                    + outsideReason(geometry, imageBounds));
                }
                geometry = clipped;
            }
            String name = roi.getName();
            if (name == null || name.trim().isEmpty()) name = "Region_" + (i + 1);
            independent.add(new SpatialRegion2D(name, geometry));
            geometries.add(geometry);
        }
        if (mode == RegionMode.INDEPENDENT) return independent;

        if (geometries.isEmpty()) {
            throw new IllegalArgumentException("no region ROI overlaps the image");
        }
        // Every element has already been through the polygonal filter, and the
        // union of polygons is polygonal, so no further filtering is needed.
        Geometry union = UnaryUnionOp.union(geometries);
        if (union == null || union.isEmpty()) {
            throw new IllegalArgumentException("the union of the region ROIs is empty");
        }
        ArrayList<SpatialRegion2D> result = new ArrayList<SpatialRegion2D>(1);
        result.add(new SpatialRegion2D("All_Regions", union));
        return result;
    }

    /** Returns the clipped region, or null when it encloses no imaged area. */
    private static Geometry clip(Geometry geometry, Geometry imageBounds) {
        if (imageBounds.covers(geometry)) return geometry;
        Geometry clipped = Polygons.polygonalOnly(geometry.intersection(imageBounds));
        if (clipped == null || clipped.isEmpty() || clipped.getArea() <= 0.0) return null;
        return clipped;
    }


    private static String outsideReason(Geometry geometry, Geometry imageBounds) {
        return geometry.intersects(imageBounds)
                ? "meets the image only along its border and encloses no pixels"
                : "lies entirely outside the image";
    }

    private static String label(String roiName, int index) {
        if (roiName == null || roiName.trim().isEmpty()) return "Region_" + (index + 1);
        return roiName;
    }

    private static Geometry toGeometry(Roi roi, double pixelWidth, double pixelHeight) {
        if (!roi.isArea()) {
            throw new IllegalArgumentException("ROI '" + roi.getName() + "' is not an area ROI");
        }
        ShapeRoi shapeRoi = new ShapeRoi(roi);
        Shape shape = shapeRoi.getShape();
        if (shape == null) throw new IllegalArgumentException("ROI has no two-dimensional shape");
        Geometry pixels = fromShape(shape);
        if (pixels == null || pixels.isEmpty()) {
            throw new IllegalArgumentException("ROI '" + roi.getName() + "' has no area");
        }
        Geometry positioned = AffineTransformation.translationInstance(
                shapeRoi.getXBase(), shapeRoi.getYBase()).transform(pixels);
        Geometry calibrated = AffineTransformation.scaleInstance(pixelWidth, pixelHeight)
                .transform(positioned);
        if (!calibrated.isValid()) calibrated = calibrated.buffer(0.0);
        if (calibrated.isEmpty() || calibrated.getArea() <= 0.0) {
            throw new IllegalArgumentException("ROI '" + roi.getName() + "' has no valid area");
        }
        return calibrated;
    }

    /**
     * Reads an AWT shape as an even-odd accumulation of its rings.
     *
     * <p>Composite ROIs — everything Create Selection, ROI Manager OR, and any
     * traced region with an excluded vessel produces — carry several subpaths.
     * Classifying those rings as shell or hole by winding direction misreads
     * ImageJ's output, dropping whole islands or rejecting holed regions
     * outright. {@link java.awt.geom.Area} normalises its contours so they
     * never overlap, which makes even-odd exact here: each ring toggles
     * whatever it encloses.
     */
    private static Geometry fromShape(Shape shape) {
        PathIterator iterator = shape.getPathIterator(null, FLATNESS);
        ArrayList<Geometry> rings = new ArrayList<Geometry>();
        ArrayList<Coordinate> current = new ArrayList<Coordinate>();
        double[] segment = new double[6];
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(segment);
            if (type == PathIterator.SEG_MOVETO) {
                addRing(rings, current);
                current = new ArrayList<Coordinate>();
                current.add(new Coordinate(segment[0], segment[1]));
            } else if (type == PathIterator.SEG_LINETO) {
                current.add(new Coordinate(segment[0], segment[1]));
            } else if (type == PathIterator.SEG_CLOSE) {
                addRing(rings, current);
                current = new ArrayList<Coordinate>();
            }
            iterator.next();
        }
        addRing(rings, current);
        return combine(rings, 0, rings.size());
    }

    /**
     * Combines rings pairwise down a balanced tree. Symmetric difference is
     * associative, so the result is identical to folding one ring at a time —
     * but folding leaves every step operating on the whole accumulated
     * geometry, which is quadratic. A thresholded tissue mask can carry
     * thousands of islands, where that difference is minutes against
     * milliseconds.
     */
    private static Geometry combine(List<Geometry> rings, int from, int to) {
        if (from >= to) return GEOMETRY_FACTORY.createPolygon();
        if (to - from == 1) return rings.get(from);
        int middle = (from + to) >>> 1;
        return combine(rings, from, middle).symDifference(combine(rings, middle, to));
    }

    private static void addRing(List<Geometry> rings, List<Coordinate> points) {
        if (points.size() < 3) return;
        ArrayList<Coordinate> ring = new ArrayList<Coordinate>(points);
        if (!ring.get(0).equals2D(ring.get(ring.size() - 1))) {
            ring.add(new Coordinate(ring.get(0)));
        }
        if (ring.size() < 4) return;
        Geometry polygon = GEOMETRY_FACTORY.createPolygon(
                ring.toArray(new Coordinate[ring.size()]));
        if (!polygon.isValid()) polygon = polygon.buffer(0.0);
        if (polygon.isEmpty()) return;
        rings.add(polygon);
    }
}
