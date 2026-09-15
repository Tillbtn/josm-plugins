// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.imagery.ImageryInfo;

import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Unit tests of {@link StacSource} and {@link StacClient#buildSearchUrl}.
 */
class StacSourceTest {

    private static StacSource brandenburgDop() {
        StacSource source = new StacSource("Brandenburg DOP", "https://isk.geobasis-bb.de/ows/aktualitaeten_wfs");
        source.setType(StacSource.Type.WFS);
        source.setCollections(Collections.singletonList("app:dop_single"));
        source.setDateProperty("creationdate");
        return source;
    }

    private static StacSource dop20() {
        StacSource source = new StacSource("Niedersachsen DOP20", "https://dop.stac.lgln.niedersachsen.de/");
        source.setCollections(Collections.singletonList("DOP"));
        source.setLayerMatch(Arrays.asList("Niedersachsen-DOP20", "niedersachsen.*dop"));
        return source;
    }

    @Test
    void testSearchUrlFromRoot() {
        assertEquals("https://dop.stac.lgln.niedersachsen.de/search", dop20().getSearchUrl());
        assertEquals("https://example.org/stac/search", new StacSource("x", "https://example.org/stac").getSearchUrl());
        assertEquals("https://example.org/search?f=json",
                new StacSource("x", "https://example.org/search?f=json").getSearchUrl());
    }

    @Test
    void testBuildSearchUrl() {
        String url = StacClient.buildSearchUrl(dop20(), new Bounds(52.36, 9.72, 52.38, 9.74), 100);
        assertEquals("https://dop.stac.lgln.niedersachsen.de/search"
                + "?bbox=9.720000,52.360000,9.740000,52.380000&limit=100&collections=DOP"
                + "&sortby=-properties.datetime", url);
    }

    @Test
    void testBuildSearchUrlWithoutSorting() {
        StacSource source = dop20();
        source.setSortByDate(false);
        String url = StacClient.buildSearchUrl(source, new Bounds(52.36, 9.72, 52.38, 9.74), 100);
        assertEquals("https://dop.stac.lgln.niedersachsen.de/search"
                + "?bbox=9.720000,52.360000,9.740000,52.380000&limit=100&collections=DOP", url);
    }

    @Test
    void testBuildSearchUrlWithQuery() {
        StacSource source = dop20();
        source.setSortByDate(false);
        source.setQuery(Json.createObjectBuilder().add("bodenpixelgroesse", "10").build());
        String url = StacClient.buildSearchUrl(source, new Bounds(52.36, 9.72, 52.38, 9.74), 100);
        assertTrue(url.endsWith("&query=%7B%22bodenpixelgroesse%22%3A%7B%22eq%22%3A%2210%22%7D%7D"), url);
    }

    @Test
    void testQueryKeepsExplicitOperators() {
        StacSource source = new StacSource("x", "https://example.org/");
        source.setQuery(Json.createObjectBuilder()
                .add("eo:cloud_cover", Json.createObjectBuilder().add("lt", 10)).build());
        assertEquals("{\"eo:cloud_cover\":{\"lt\":10}}", source.getQuery().toString());
    }

    @Test
    void testMatchesLayer() {
        StacSource source = dop20();
        ImageryInfo info = new ImageryInfo("Niedersachsen DOP20",
                "https://opendata.lgln.niedersachsen.de/doorman/noauth/dop_wms?LAYERS=ni_dop20");
        info.setId("Niedersachsen-DOP20");
        assertTrue(source.matchesLayer(info));
        assertFalse(source.matchesLayer(new ImageryInfo("Bing aerial imagery", "https://example.org/bing")));
        assertFalse(source.matchesLayer(null));
        // A source without patterns is never assigned automatically
        StacSource withoutPatterns = new StacSource("x", "https://example.org/");
        assertFalse(withoutPatterns.matchesLayer(info));
    }

    @Test
    void testJsonRoundTrip() {
        StacSource source = dop20();
        source.setDetailProperties(Arrays.asList("bildflugname", "bodenpixelgroesse"));
        source.setColor(new Color(0xE8, 0x59, 0x0C));
        source.setAttribution("LGLN");
        source.setDateProperty("acquired");
        source.setEnabled(false);
        source.setSortByDate(false);
        source.setQuery(Json.createObjectBuilder().add("bodenpixelgroesse", "20").build());

        JsonObject json = source.toJson();
        StacSource read = StacSource.fromJson(json);
        assertNotNull(read);
        assertEquals(source, read);
        assertEquals("acquired", read.getDateProperty());
        assertFalse(read.isEnabled());
        assertFalse(read.isSortByDate());
    }

    @Test
    void testMinimalJson() {
        StacSource source = StacSource.fromJson(Json.createObjectBuilder()
                .add("name", "Test").add("url", "https://example.org/").build());
        assertNotNull(source);
        assertEquals(StacSource.DEFAULT_DATE_PROPERTY, source.getDateProperty());
        assertTrue(source.isEnabled());
        assertTrue(source.isSortByDate());
        assertTrue(source.getCollections().isEmpty());
    }

    @Test
    void testInvalidJsonIsIgnored() {
        assertEquals(null, StacSource.fromJson(Json.createObjectBuilder().add("name", "no url").build()));
        assertEquals(null, StacSource.fromJson(Json.createObjectBuilder().add("url", "https://example.org/").build()));
    }

    @Test
    void testStringValueInsteadOfArray() {
        StacSource source = StacSource.fromJson(Json.createObjectBuilder()
                .add("name", "Test").add("url", "https://example.org/").add("collections", "DOP").build());
        assertNotNull(source);
        List<String> collections = source.getCollections();
        assertEquals(1, collections.size());
        assertEquals("DOP", collections.get(0));
    }

    @Test
    void testWfsUrlIsUsedUnchanged() {
        // /search must not be appended to the URL of a WFS
        assertEquals("https://isk.geobasis-bb.de/ows/aktualitaeten_wfs", brandenburgDop().getSearchUrl());
    }

    @Test
    void testBuildWfsUrl() {
        String url = StacClient.buildWfsUrl(brandenburgDop(), "app:dop_single",
                new Bounds(52.36, 9.72, 52.38, 9.74), 100, 0, true);
        assertEquals("https://isk.geobasis-bb.de/ows/aktualitaeten_wfs"
                + "?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature"
                + "&TYPENAMES=app%3Adop_single"
                + "&OUTPUTFORMAT=application%2Fgeo%2Bjson"
                + "&SRSNAME=urn%3Aogc%3Adef%3Acrs%3AEPSG%3A%3A4326"
                // BBOX in the axis order of EPSG:4326, which is latitude first
                + "&BBOX=52.360000,9.720000,52.380000,9.740000,urn%3Aogc%3Adef%3Acrs%3AEPSG%3A%3A4326"
                + "&COUNT=100&SORTBY=creationdate+D", url);
    }

    @Test
    void testBuildWfsUrlOfLaterPage() {
        String url = StacClient.buildWfsUrl(brandenburgDop(), "app:dop_single",
                new Bounds(52.36, 9.72, 52.38, 9.74), 200, 400, false);
        assertTrue(url.endsWith("&COUNT=200&STARTINDEX=400"), url);
        assertFalse(url.contains("SORTBY"), url);
    }

    @Test
    void testBuildWfsUrlKeepsExistingParameters() {
        StacSource source = brandenburgDop();
        source.setUrl("https://example.org/ows?map=/data/dop.map");
        String url = StacClient.buildWfsUrl(source, "dop", new Bounds(52.36, 9.72, 52.38, 9.74), 10, 0, false);
        assertTrue(url.startsWith("https://example.org/ows?map=/data/dop.map&SERVICE=WFS"), url);
    }

    @Test
    void testWfsJsonRoundTrip() {
        StacSource source = brandenburgDop();
        JsonObject json = source.toJson();
        assertEquals("wfs", json.getString("type"));
        StacSource read = StacSource.fromJson(json);
        assertNotNull(read);
        assertEquals(source, read);
        assertTrue(read.isWfs());
        assertEquals("creationdate", read.getDateProperty());
    }

    @Test
    void testStacIsTheDefaultTypeAndNotWritten() {
        assertFalse(dop20().toJson().containsKey("type"));
        assertEquals(StacSource.Type.STAC, dop20().getType());
        assertFalse(dop20().isWfs());
        // An unknown type falls back to STAC instead of dropping the source
        StacSource unknown = StacSource.fromJson(Json.createObjectBuilder()
                .add("name", "x").add("url", "https://example.org/").add("type", "wms").build());
        assertNotNull(unknown);
        assertEquals(StacSource.Type.STAC, unknown.getType());
    }

    @Test
    void testTypeIsPartOfTheIdentity() {
        StacSource wfs = brandenburgDop();
        StacSource stac = wfs.copy();
        stac.setType(StacSource.Type.STAC);
        assertFalse(wfs.equals(stac));
        assertEquals(wfs, wfs.copy());
    }
}
