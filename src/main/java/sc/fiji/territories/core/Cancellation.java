package sc.fiji.territories.core;

import java.util.function.BooleanSupplier;

/**
 * The engines' one way of asking "should I stop?".
 *
 * <p>Engines poll a caller's {@link BooleanSupplier} at row, slice, object or
 * permutation granularity and stop with {@link ComputationCancelledException}
 * when it returns {@code true}. A poll only reads; it never changes the order
 * or the values of any arithmetic, so outputs are bit-identical with or
 * without a check. The supplier may be called from worker threads and must
 * therefore be thread-safe and cheap (a volatile read, for example).
 */
final class Cancellation {

    /** Never cancels; what the overloads without a check use. */
    static final BooleanSupplier NEVER = () -> false;

    /** Voxels or pixels visited between polls inside long flat loops. */
    static final int POLL_MASK = 0xFFF;

    private Cancellation() {
    }

    static BooleanSupplier orNever(BooleanSupplier cancelled) {
        return cancelled == null ? NEVER : cancelled;
    }

    static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new ComputationCancelledException();
    }
}
