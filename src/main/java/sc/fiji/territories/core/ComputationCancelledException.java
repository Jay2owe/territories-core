package sc.fiji.territories.core;

/**
 * Thrown when the cancellation check a caller passed to an engine returns
 * {@code true} part-way through a computation. Nothing the computation was
 * building is returned; every array it allocated is left for the garbage
 * collector.
 *
 * @since 0.2.1
 */
public final class ComputationCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ComputationCancelledException() {
        super("territories computation was cancelled");
    }
}
