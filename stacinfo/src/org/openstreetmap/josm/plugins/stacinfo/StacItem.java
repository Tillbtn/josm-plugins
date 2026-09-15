// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stacinfo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.tools.Logging;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * One STAC item (in the case of orthophotos usually one image tile) with its footprint and date.
 */
public class StacItem {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final String id;
    private final String collection;
    private final Instant date;
    private final String rawDate;
    private final Map<String, String> properties;
    private final Map<String, String> assets;
    private final List<List<LatLon>> rings;
    private final Bounds bounds;

    StacItem(String id, String collection, Instant date, String rawDate, Map<String, String> properties,
            Map<String, String> assets, List<List<LatLon>> rings, Bounds bounds) {
        this.id = id;
        this.collection = collection;
        this.date = date;
        this.rawDate = rawDate;
        this.properties = properties;
        this.assets = assets;
        this.rings = rings;
        this.bounds = bounds;
    }

    /**
     * Reads one GeoJSON feature of a STAC {@code FeatureCollection}.
     * @param feature the feature
     * @param dateProperty the item property holding the acquisition date
     * @return the item, or {@code null} if it has no usable geometry
     */
    public static StacItem fromJson(JsonObject feature, String dateProperty) {
        JsonObject props = feature.getJsonObject("properties");
        Map<String, String> properties = new LinkedHashMap<>();
        if (props != null) {
            for (Map.Entry<String, JsonValue> entry : props.entrySet()) {
                String value = asString(entry.getValue());
                if (value != null) {
                    properties.put(entry.getKey(), value);
                }
            }
        }
        String rawDate = firstNonEmpty(properties, dateProperty, StacSource.DEFAULT_DATE_PROPERTY,
                "start_datetime", "end_datetime", "created", "updated");
        List<List<LatLon>> rings = readGeometry(feature.get("geometry"));
        Bounds bounds = readBbox(feature.get("bbox"));
        if (bounds == null) {
            bounds = boundsOf(rings);
        }
        if (rings.isEmpty() && bounds != null) {
            rings = Collections.singletonList(cornersOf(bounds));
        }
        if (bounds == null) {
            Logging.warn("stacinfo: STAC item without geometry: " + feature.getString("id", "?"));
            return null;
        }
        return new StacItem(feature.getString("id", ""), feature.getString("collection", ""),
                parseDate(rawDate), rawDate, properties, readAssets(feature.getJsonObject("assets")), rings, bounds);
    }

    private static Map<String, String> readAssets(JsonObject assets) {
        Map<String, String> result = new LinkedHashMap<>();
        if (assets != null) {
            for (Map.Entry<String, JsonValue> entry : assets.entrySet()) {
                if (entry.getValue() instanceof JsonObject) {
                    String href = ((JsonObject) entry.getValue()).getString("href", "");
                    if (!href.isEmpty()) {
                        result.put(entry.getKey(), href);
                    }
                }
            }
        }
        return result;
    }

    private static String firstNonEmpty(Map<String, String> properties, String... keys) {
        for (String key : keys) {
            String value = properties.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String asString(JsonValue value) {
        switch (value.getValueType()) {
            case STRING:
                return ((JsonString) value).getString();
            case NUMBER:
                return value.toString();
            case TRUE:
                return "true";
            case FALSE:
                return "false";
            case ARRAY:
            case OBJECT:
                return value.toString();
            default:
                return null;
        }
    }

    /**
     * Parses the different date formats found in STAC items.
     * @param text the date, for example {@code 2025-03-06T00:00:00Z}
     * @return the parsed instant, or {@code null} if it cannot be parsed
     */
    public static Instant parseDate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String value = text.trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            Logging.trace(e);
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            Logging.trace(e);
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            Logging.trace(e);
        }
        Logging.warn("stacinfo: cannot parse STAC date " + value);
        return null;
    }

    private static List<List<LatLon>> readGeometry(JsonValue geometry) {
        List<List<LatLon>> rings = new ArrayList<>();
        if (!(geometry instanceof JsonObject)) {
            return rings;
        }
        JsonObject object = (JsonObject) geometry;
        String type = object.getString("type", "");
        JsonArray coordinates = object.getJsonArray("coordinates");
        if (coordinates == null) {
            return rings;
        }
        if ("Polygon".equals(type)) {
            addOuterRing(rings, coordinates);
        } else if ("MultiPolygon".equals(type)) {
            for (JsonValue polygon : coordinates) {
                if (polygon instanceof JsonArray) {
                    addOuterRing(rings, (JsonArray) polygon);
                }
            }
        }
        return rings;
    }

    private static void addOuterRing(List<List<LatLon>> rings, JsonArray polygon) {
        if (polygon.isEmpty() || !(polygon.get(0) instanceof JsonArray)) {
            return;
        }
        List<LatLon> ring = new ArrayList<>();
        for (JsonValue point : polygon.getJsonArray(0)) {
            if (point instanceof JsonArray) {
                JsonArray coordinate = (JsonArray) point;
                if (coordinate.size() >= 2) {
                    ring.add(new LatLon(coordinate.getJsonNumber(1).doubleValue(), coordinate.getJsonNumber(0).doubleValue()));
                }
            }
        }
        if (ring.size() >= 3) {
            rings.add(ring);
        }
    }

    private static Bounds readBbox(JsonValue value) {
        if (!(value instanceof JsonArray)) {
            return null;
        }
        JsonArray array = (JsonArray) value;
        double[] numbers = new double[array.size()];
        for (int i = 0; i < array.size(); i++) {
            if (!(array.get(i) instanceof JsonNumber)) {
                return null;
            }
            numbers[i] = array.getJsonNumber(i).doubleValue();
        }
        if (numbers.length == 4) {
            return new Bounds(numbers[1], numbers[0], numbers[3], numbers[2]);
        }
        if (numbers.length == 6) {
            // [west, south, min elevation, east, north, max elevation]
            return new Bounds(numbers[1], numbers[0], numbers[4], numbers[3]);
        }
        return null;
    }

    private static Bounds boundsOf(List<List<LatLon>> rings) {
        Bounds bounds = null;
        for (List<LatLon> ring : rings) {
            for (LatLon point : ring) {
                if (bounds == null) {
                    bounds = new Bounds(point);
                } else {
                    bounds.extend(point);
                }
            }
        }
        return bounds;
    }

    private static List<LatLon> cornersOf(Bounds bounds) {
        return List.of(
                new LatLon(bounds.getMinLat(), bounds.getMinLon()),
                new LatLon(bounds.getMinLat(), bounds.getMaxLon()),
                new LatLon(bounds.getMaxLat(), bounds.getMaxLon()),
                new LatLon(bounds.getMaxLat(), bounds.getMinLon()));
    }

    public String getId() {
        return id;
    }

    public String getCollection() {
        return collection;
    }

    /**
     * Returns the acquisition date.
     * @return the date, or {@code null} if the item has none
     */
    public Instant getDate() {
        return date;
    }

    /**
     * Returns the acquisition date as {@code yyyy-MM-dd}.
     * @return the formatted date, the raw value if it cannot be parsed, or an empty string
     */
    public String getDateLabel() {
        if (date != null) {
            return DATE_FORMAT.format(date);
        }
        return rawDate == null ? "" : rawDate;
    }

    public String getRawDate() {
        return rawDate;
    }

    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    /**
     * Returns one item property.
     * @param key the property name
     * @return the value, or an empty string if the item does not have that property
     */
    public String getProperty(String key) {
        return properties.getOrDefault(key, "");
    }

    /**
     * Returns the files offered for this item.
     * @return the assets, mapping the asset name to its URL
     */
    public Map<String, String> getAssets() {
        return Collections.unmodifiableMap(assets);
    }

    public List<List<LatLon>> getRings() {
        return Collections.unmodifiableList(rings);
    }

    public Bounds getBounds() {
        return bounds;
    }
}
