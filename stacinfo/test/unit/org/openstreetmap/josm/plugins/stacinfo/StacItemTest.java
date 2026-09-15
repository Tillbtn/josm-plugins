// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

/**
 * Unit tests of {@link StacItem}, based on a recorded answer of the DOP20 endpoint of Niedersachsen.
 */
class StacItemTest {

    private static List<StacItem> readItems() throws IOException {
        List<StacItem> items = new ArrayList<>();
        try (InputStream in = StacItemTest.class.getResourceAsStream("/dop_search.json");
             JsonReader reader = Json.createReader(in)) {
            for (JsonValue feature : reader.readObject().getJsonArray("features")) {
                items.add(StacItem.fromJson((JsonObject) feature, StacSource.DEFAULT_DATE_PROPERTY));
            }
        }
        return items;
    }

    @Test
    void testParseItems() throws IOException {
        List<StacItem> items = readItems();
        assertEquals(3, items.size());
        StacItem item = items.get(0);
        assertNotNull(item.getDate());
        assertEquals(10, item.getDateLabel().length());
        assertEquals("DOP", item.getCollection());
        assertTrue(item.getId().startsWith("dop20rgbi"));
        assertFalse(item.getRings().isEmpty());
        assertTrue(item.getRings().get(0).size() >= 4);
        assertNotNull(item.getBounds());
        assertTrue(item.getBounds().getMinLat() > 52 && item.getBounds().getMaxLat() < 53);
        assertEquals("Hannover", item.getProperty("bildflugname"));
        assertEquals("", item.getProperty("does-not-exist"));
    }

    @Test
    void testParseDate() {
        assertEquals(Instant.parse("2025-03-06T00:00:00Z"), StacItem.parseDate("2025-03-06T00:00:00Z"));
        assertEquals(Instant.parse("2026-09-05T10:36:13.031Z"), StacItem.parseDate("2026-09-05T10:36:13.031000Z"));
        assertEquals(Instant.parse("2012-03-15T00:00:00Z"), StacItem.parseDate("2012-03-15T00:00:00+00:00"));
        assertEquals(Instant.parse("2020-01-02T00:00:00Z"), StacItem.parseDate("2020-01-02"));
        assertNull(StacItem.parseDate(""));
        assertNull(StacItem.parseDate(null));
        assertNull(StacItem.parseDate("not a date"));
    }

    @Test
    void testDateLabelFallsBackToRawValue() {
        JsonObject feature = Json.createObjectBuilder()
                .add("id", "x")
                .add("bbox", Json.createArrayBuilder().add(9.0).add(52.0).add(9.1).add(52.1))
                .add("properties", Json.createObjectBuilder().add("datetime", "spring 2024"))
                .build();
        StacItem item = StacItem.fromJson(feature, StacSource.DEFAULT_DATE_PROPERTY);
        assertNotNull(item);
        assertNull(item.getDate());
        assertEquals("spring 2024", item.getDateLabel());
        // The bbox is used as footprint if the item has no geometry
        assertEquals(1, item.getRings().size());
        assertEquals(4, item.getRings().get(0).size());
    }
}
