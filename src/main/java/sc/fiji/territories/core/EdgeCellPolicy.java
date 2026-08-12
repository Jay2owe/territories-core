package sc.fiji.territories.core;

/** Controls whether Voronoi cells touching a region boundary enter summaries. */
public enum EdgeCellPolicy {
    /** Return every cell and mark boundary-touching cells explicitly. */
    INCLUDE_FLAGGED,
    /** Return edge cells but omit them from summary statistics. */
    EXCLUDE_FROM_SUMMARIES
}

