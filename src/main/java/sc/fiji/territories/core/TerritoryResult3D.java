package sc.fiji.territories.core;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Voxel-resolved 3D territories and summaries for one mask region. */
public final class TerritoryResult3D {

    private final String regionName;
    private final List<TerritoryCell3D> cells;
    private final RegularityResult regularity;
    private final ImagePlus territoryLabels;

    TerritoryResult3D(
            String regionName,
            List<TerritoryCell3D> cells,
            RegularityResult regularity,
            ImagePlus territoryLabels) {
        this.regionName = regionName;
        this.cells = Collections.unmodifiableList(
                new ArrayList<TerritoryCell3D>(cells));
        this.regularity = regularity;
        this.territoryLabels = territoryLabels;
    }

    public String getRegionName() {
        return regionName;
    }

    public List<TerritoryCell3D> getCells() {
        return cells;
    }

    public RegularityResult getRegularity() {
        return regularity;
    }

    /**
     * Returns a 32-bit stack whose positive values are global object index + 1.
     * The caller owns this image.
     */
    public ImagePlus getTerritoryLabels() {
        return territoryLabels;
    }
}

