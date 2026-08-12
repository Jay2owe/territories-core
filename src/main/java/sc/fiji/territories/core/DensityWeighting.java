package sc.fiji.territories.core;

/** Defines what each object's kernel contributes to a density map. */
public enum DensityWeighting {
    /** Every object contributes one unit, independent of its size. */
    OBJECT_COUNT,
    /**
     * Every object contributes its calibrated size: area in 2D or volume in
     * 3D.
     */
    OBJECT_SIZE,
    /**
     * Compatibility alias for {@link #OBJECT_SIZE}.
     *
     * @deprecated Use {@link #OBJECT_SIZE}; in 3D the size is volume.
     */
    @Deprecated
    OBJECT_AREA
}
