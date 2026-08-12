package sc.fiji.territories.core;

import ij.ImagePlus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One calibrated density map and per-object leave-one-out density values. */
public final class DensityResult {

    private final String regionName;
    private final String typeName;
    private final DensityWeighting weighting;
    private final DensityBoundaryMode boundaryMode;
    private final double bandwidthMicrons;
    private final ImagePlus densityMap;
    private final Map<Integer, Double> localDensityByObjectIndex;

    DensityResult(
            String regionName,
            String typeName,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode,
            double bandwidthMicrons,
            ImagePlus densityMap,
            Map<Integer, Double> localDensityByObjectIndex) {
        this.regionName = regionName;
        this.typeName = typeName;
        this.weighting = weighting;
        this.boundaryMode = boundaryMode;
        this.bandwidthMicrons = bandwidthMicrons;
        this.densityMap = densityMap;
        this.localDensityByObjectIndex = Collections.unmodifiableMap(
                new LinkedHashMap<Integer, Double>(localDensityByObjectIndex));
    }

    public String getRegionName() {
        return regionName;
    }

    public String getTypeName() {
        return typeName;
    }

    public DensityWeighting getWeighting() {
        return weighting;
    }

    public DensityBoundaryMode getBoundaryMode() {
        return boundaryMode;
    }

    public double getBandwidthMicrons() {
        return bandwidthMicrons;
    }

    /**
     * Returns the generated 32-bit image. The caller owns this image and is
     * responsible for closing it.
     */
    public ImagePlus getDensityMap() {
        return densityMap;
    }

    /**
     * Returns density at each object's centroid with that object's own kernel
     * omitted, so the feature measures its local environment.
     */
    public Map<Integer, Double> getLocalDensityByObjectIndex() {
        return localDensityByObjectIndex;
    }
}

