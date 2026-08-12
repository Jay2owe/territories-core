package sc.fiji.territories.core;

import ij.ImagePlus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One calibrated 3D density volume and leave-one-out object densities. */
public final class DensityResult3D {

    private final String regionName;
    private final String typeName;
    private final DensityWeighting weighting;
    private final DensityBoundaryMode boundaryMode;
    private final double bandwidth;
    private final ImagePlus densityVolume;
    private final Map<Integer, Double> localDensityByObjectIndex;

    DensityResult3D(
            String regionName,
            String typeName,
            DensityWeighting weighting,
            DensityBoundaryMode boundaryMode,
            double bandwidth,
            ImagePlus densityVolume,
            Map<Integer, Double> localDensityByObjectIndex) {
        this.regionName = regionName;
        this.typeName = typeName;
        this.weighting = weighting;
        this.boundaryMode = boundaryMode;
        this.bandwidth = bandwidth;
        this.densityVolume = densityVolume;
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

    public double getBandwidth() {
        return bandwidth;
    }

    /** Returns a caller-owned 32-bit density stack. */
    public ImagePlus getDensityVolume() {
        return densityVolume;
    }

    public Map<Integer, Double> getLocalDensityByObjectIndex() {
        return localDensityByObjectIndex;
    }
}

