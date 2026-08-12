package sc.fiji.territories.core;

/** Defines how multiple region ROIs are interpreted. */
public enum RegionMode {
    /** Each named ROI is analysed as a separate spatial domain. */
    INDEPENDENT,
    /** All supplied ROIs are combined into one spatial domain. */
    UNION
}

