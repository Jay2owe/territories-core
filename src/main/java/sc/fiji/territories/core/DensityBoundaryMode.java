package sc.fiji.territories.core;

/** Controls kernel-density correction at region boundaries. */
public enum DensityBoundaryMode {
    /** Divide by the fraction of each kernel supported inside the region. */
    CORRECTED,
    /** Discard kernel mass outside the region without correcting edge bias. */
    CLIPPED
}

