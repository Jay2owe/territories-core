package sc.fiji.territories.core;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Balanced 3D k-d tree for repeated calibrated nearest-centroid queries.
 *
 * <p>The tree is immutable. {@link #nearest} keeps its search state in this
 * instance and is for one thread; concurrent callers each take their own
 * {@link #query()}.
 */
final class NearestCentroid3D {

    private final Node root;
    private final Query shared;

    NearestCentroid3D(List<SpatialObject3D> objects) {
        if (objects == null || objects.isEmpty()) {
            throw new IllegalArgumentException("k-d tree requires at least one object");
        }
        SpatialObject3D[] values = objects.toArray(new SpatialObject3D[0]);
        this.root = build(values, 0, values.length, 0);
        this.shared = new Query();
    }

    /** Nearest centroid, lowest global index on ties. Not thread-safe; see {@link #query()}. */
    SpatialObject3D nearest(double x, double y, double z) {
        return shared.nearest(x, y, z);
    }

    /** A new query over this tree with its own search state, for one worker thread. */
    Query query() {
        return new Query();
    }

    /** Search state for one thread; answers exactly as {@link NearestCentroid3D#nearest}. */
    final class Query {
        private SpatialObject3D bestObject;
        private double bestSquaredDistance;

        private Query() {
        }

        SpatialObject3D nearest(double x, double y, double z) {
            bestObject = null;
            bestSquaredDistance = Double.POSITIVE_INFINITY;
            search(root, x, y, z);
            return bestObject;
        }

        private void search(Node node, double x, double y, double z) {
            if (node == null) return;
            double dx = x - node.object.getCentroidX();
            double dy = y - node.object.getCentroidY();
            double dz = z - node.object.getCentroidZ();
            double squared = dx * dx + dy * dy + dz * dz;
            if (squared < bestSquaredDistance
                    || (squared == bestSquaredDistance
                    && (bestObject == null
                    || node.object.getIndex() < bestObject.getIndex()))) {
                bestSquaredDistance = squared;
                bestObject = node.object;
            }

            double difference = coordinate(x, y, z, node.axis)
                    - coordinate(node.object, node.axis);
            Node near = difference <= 0.0 ? node.left : node.right;
            Node far = difference <= 0.0 ? node.right : node.left;
            search(near, x, y, z);
            if (difference * difference <= bestSquaredDistance) {
                search(far, x, y, z);
            }
        }
    }

    private static Node build(
            SpatialObject3D[] objects, int from, int to, int depth) {
        if (from >= to) return null;
        final int axis = depth % 3;
        Arrays.sort(objects, from, to, new Comparator<SpatialObject3D>() {
            @Override
            public int compare(SpatialObject3D first, SpatialObject3D second) {
                int comparison = Double.compare(
                        coordinate(first, axis), coordinate(second, axis));
                return comparison != 0
                        ? comparison
                        : Integer.compare(first.getIndex(), second.getIndex());
            }
        });
        int middle = (from + to) >>> 1;
        return new Node(
                objects[middle],
                axis,
                build(objects, from, middle, depth + 1),
                build(objects, middle + 1, to, depth + 1));
    }

    private static double coordinate(SpatialObject3D object, int axis) {
        if (axis == 0) return object.getCentroidX();
        if (axis == 1) return object.getCentroidY();
        return object.getCentroidZ();
    }

    private static double coordinate(double x, double y, double z, int axis) {
        if (axis == 0) return x;
        if (axis == 1) return y;
        return z;
    }

    private static final class Node {
        private final SpatialObject3D object;
        private final int axis;
        private final Node left;
        private final Node right;

        private Node(SpatialObject3D object, int axis, Node left, Node right) {
            this.object = object;
            this.axis = axis;
            this.left = left;
            this.right = right;
        }
    }
}

