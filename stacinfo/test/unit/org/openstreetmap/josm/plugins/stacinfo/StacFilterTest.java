// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

/**
 * Unit tests of {@link StacFilter}, the free text filter of the dialog.
 */
class StacFilterTest {

    private static StacItem item(String id, String date, String... properties) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < properties.length; i += 2) {
            map.put(properties[i], properties[i + 1]);
        }
        List<LatLon> ring = new ArrayList<>();
        ring.add(new LatLon(53.0, 10.0));
        ring.add(new LatLon(53.0, 10.1));
        ring.add(new LatLon(53.1, 10.1));
        return new StacItem(id, "DOP", StacItem.parseDate(date), date, map, Collections.emptyMap(),
                Collections.singletonList(ring), new Bounds(53.0, 10.0, 53.1, 10.1));
    }

    private static final StacItem DOP10 = item("dop10rgbi_32_594_5901_1_ni_2026-03-18", "2026-03-18",
            "bodenpixelgroesse", "10", "bildflugname", "Lueneburg");
    private static final StacItem DOP20 = item("dop20rgbi_32_594_5901_1_ni_2026-03-18", "2026-03-18",
            "bodenpixelgroesse", "20", "bildflugname", "Lueneburg");
    private static final StacItem OLD20 = item("dop20rgbi_32_594_5900_2_ni_2012-05-24", "2012-05-24",
            "bodenpixelgroesse", "20", "bildflugname", "Lueneburg");

    @Test
    void testEmptyFilterMatchesEverything() {
        assertTrue(StacFilter.parse(null).isEmpty());
        assertTrue(StacFilter.parse("   ").isEmpty());
        assertTrue(StacFilter.parse("").matches(DOP10));
    }

    @Test
    void testTextMatchesId() {
        StacFilter filter = StacFilter.parse("dop10");
        assertTrue(filter.matches(DOP10));
        assertFalse(filter.matches(DOP20));
    }

    @Test
    void testTextMatchesPropertyAndDate() {
        assertTrue(StacFilter.parse("lueneburg").matches(DOP20));
        assertTrue(StacFilter.parse("2012").matches(OLD20));
        assertFalse(StacFilter.parse("2012").matches(DOP20));
    }

    @Test
    void testPropertyFilter() {
        StacFilter filter = StacFilter.parse("bodenpixelgroesse=20");
        assertTrue(filter.matches(DOP20));
        assertFalse(filter.matches(DOP10));
        // The key may be abbreviated
        assertTrue(StacFilter.parse("boden=10").matches(DOP10));
        // ... but it has to be a property, not the id
        assertFalse(StacFilter.parse("bodenpixelgroesse=dop20").matches(DOP20));
    }

    @Test
    void testNegation() {
        assertTrue(StacFilter.parse("-dop10").matches(DOP20));
        assertFalse(StacFilter.parse("-dop10").matches(DOP10));
        assertFalse(StacFilter.parse("-bodenpixelgroesse=20").matches(OLD20));
    }

    @Test
    void testAllPartsHaveToMatch() {
        StacFilter filter = StacFilter.parse("bodenpixelgroesse=20 2026 -dop10");
        assertTrue(filter.matches(DOP20));
        assertFalse(filter.matches(OLD20));
        assertFalse(filter.matches(DOP10));
    }

    @Test
    void testFilterList() {
        List<StacItem> items = new ArrayList<>();
        items.add(DOP10);
        items.add(DOP20);
        items.add(OLD20);
        assertEquals(2, StacFilter.parse("2026-03-18").filter(items).size());
        assertEquals(3, StacFilter.EMPTY.filter(items).size());
    }

    @Test
    void testFilterOnRealAnswer() throws IOException {
        List<StacItem> items = new ArrayList<>();
        try (InputStream in = StacFilterTest.class.getResourceAsStream("/dop_search.json");
             JsonReader reader = Json.createReader(in)) {
            for (JsonValue feature : reader.readObject().getJsonArray("features")) {
                items.add(StacItem.fromJson((JsonObject) feature, StacSource.DEFAULT_DATE_PROPERTY));
            }
        }
        assertEquals(items.size(), StacFilter.parse("bodenpixelgroesse=20").filter(items).size());
        assertTrue(StacFilter.parse("bodenpixelgroesse=10").filter(items).isEmpty());
        assertFalse(items.get(0).getAssets().isEmpty(), "the assets of an item are read");
    }
}
