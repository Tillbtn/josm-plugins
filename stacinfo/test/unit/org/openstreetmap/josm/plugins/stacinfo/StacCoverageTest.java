// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;

/**
 * Unit tests of {@link StacCoverage}, the filter that reduces an archive to its most recent state.
 */
class StacCoverageTest {

    private static StacItem tile(String id, String date, double minLat, double minLon, double maxLat, double maxLon) {
        List<LatLon> ring = new ArrayList<>();
        ring.add(new LatLon(minLat, minLon));
        ring.add(new LatLon(minLat, maxLon));
        ring.add(new LatLon(maxLat, maxLon));
        ring.add(new LatLon(maxLat, minLon));
        return new StacItem(id, "test", Instant.parse(date + "T00:00:00Z"), date, Collections.emptyMap(),
                Collections.emptyMap(), Collections.singletonList(ring), new Bounds(minLat, minLon, maxLat, maxLon));
    }

    private static List<String> ids(List<StacItem> items) {
        return items.stream().map(StacItem::getId).collect(Collectors.toList());
    }

    @Test
    void testFullyCoveredItemIsDropped() {
        // One old 2x2 tile, completely covered by four new 1x1 tiles
        List<StacItem> items = new ArrayList<>();
        items.add(tile("new-a", "2026-03-18", 53.0, 10.0, 53.1, 10.1));
        items.add(tile("new-b", "2026-03-18", 53.0, 10.1, 53.1, 10.2));
        items.add(tile("new-c", "2026-03-18", 53.1, 10.0, 53.2, 10.1));
        items.add(tile("new-d", "2026-03-18", 53.1, 10.1, 53.2, 10.2));
        items.add(tile("old", "2012-05-24", 53.0, 10.0, 53.2, 10.2));

        List<StacItem> latest = StacCoverage.latest(items);
        assertEquals(4, latest.size());
        assertTrue(ids(latest).stream().allMatch(id -> id.startsWith("new-")));
    }

    @Test
    void testPartiallyCoveredItemIsKept() {
        List<StacItem> items = new ArrayList<>();
        items.add(tile("new", "2026-03-18", 53.0, 10.0, 53.1, 10.1));
        items.add(tile("old", "2012-05-24", 53.0, 10.0, 53.2, 10.2));

        assertEquals(List.of("new", "old"), ids(StacCoverage.latest(items)));
    }

    @Test
    void testNeighbouringTilesAreAllKept() {
        List<StacItem> items = new ArrayList<>();
        items.add(tile("left", "2026-03-18", 53.0, 10.0, 53.1, 10.1));
        items.add(tile("right", "2026-03-18", 53.0, 10.1, 53.1, 10.2));

        assertEquals(2, StacCoverage.latest(items).size());
    }

    @Test
    void testDuplicateOfSameDateIsDropped() {
        // The DOP collection of the LGLN offers 10 cm and 20 cm products of the same flight
        List<StacItem> items = new ArrayList<>();
        items.add(tile("dop10", "2026-03-18", 53.0, 10.0, 53.1, 10.1));
        items.add(tile("dop20", "2026-03-18", 53.0, 10.0, 53.1, 10.1));

        assertEquals(List.of("dop10"), ids(StacCoverage.latest(items)));
    }

    @Test
    void testEmptyInput() {
        assertTrue(StacCoverage.latest(Collections.emptyList()).isEmpty());
    }

    @Test
    void testPolygonArea() {
        StacItem item = tile("x", "2026-03-18", 53.0, 10.0, 53.2, 10.4);
        assertEquals(0.08, StacCoverage.polygonArea(StacCoverage.toShape(item)), 1e-9);
    }
}
