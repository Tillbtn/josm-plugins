// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.data.preferences.DoubleProperty;
import org.openstreetmap.josm.data.preferences.IntegerProperty;

/**
 * Preferences of the stacinfo plugin.
 */
public final class StacSettings {

    /** Reload the STAC items automatically when the map view changes. */
    public static final BooleanProperty AUTO_REFRESH = new BooleanProperty("stacinfo.auto-refresh", true);

    /** Show only the most recent item of every place instead of the whole archive. */
    public static final BooleanProperty LATEST_ONLY = new BooleanProperty("stacinfo.latest-only", true);

    /** Draw the acquisition date into every footprint. */
    public static final BooleanProperty SHOW_LABELS = new BooleanProperty("stacinfo.show-labels", true);

    /** Draw the colour legend into the map view. */
    public static final BooleanProperty SHOW_LEGEND = new BooleanProperty("stacinfo.show-legend", true);

    /** Maximum number of items requested for one map view. */
    public static final IntegerProperty MAX_ITEMS = new IntegerProperty("stacinfo.max-items", 500);

    /** Opacity (0-255) used to fill the footprints. */
    public static final IntegerProperty FILL_ALPHA = new IntegerProperty("stacinfo.fill-alpha", 45);

    /** Largest map view (in square degrees) that is queried. */
    public static final DoubleProperty MAX_AREA = new DoubleProperty("stacinfo.max-area", 0.15);

    /** Milliseconds between the last map view change and the automatic reload. */
    public static final IntegerProperty REFRESH_DELAY = new IntegerProperty("stacinfo.refresh-delay", 700);

    private StacSettings() {
        // Hide public constructor of this utility class
    }
}
