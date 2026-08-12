package sc.fiji.territories.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded territories and summary measurements for one region. */
public final class TerritoryResult {

    private final String regionName;
    private final List<TerritoryCell> cells;
    private final RegularityResult regularity;

    TerritoryResult(String regionName, List<TerritoryCell> cells, RegularityResult regularity) {
        this.regionName = regionName;
        this.cells = Collections.unmodifiableList(new ArrayList<TerritoryCell>(cells));
        this.regularity = regularity;
    }

    public String getRegionName() {
        return regionName;
    }

    public List<TerritoryCell> getCells() {
        return cells;
    }

    public RegularityResult getRegularity() {
        return regularity;
    }
}

